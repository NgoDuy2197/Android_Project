import 'package:flutter/material.dart';
import 'package:mobile_scanner/mobile_scanner.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'broadcaster.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const CameraBroadcastApp());
}

class CameraBroadcastApp extends StatelessWidget {
  const CameraBroadcastApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Camera WebRTC',
      debugShowCheckedModeBanner: false,
      theme: ThemeData.dark(useMaterial3: true).copyWith(
        scaffoldBackgroundColor: const Color(0xFF0F1115),
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF30A46C),
          brightness: Brightness.dark,
        ),
      ),
      home: const HomePage(),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  static const _prefsKey = 'server_address';

  final _broadcaster = Broadcaster();
  final _addressCtrl = TextEditingController();
  BroadcastStatus _status = BroadcastStatus.idle;
  String _message = '';

  @override
  void initState() {
    super.initState();
    _broadcaster.onStatus = (status, message) {
      if (!mounted) return;
      setState(() {
        _status = status;
        _message = message;
      });
    };
    _loadAndAutoConnect();
  }

  Future<void> _loadAndAutoConnect() async {
    final prefs = await SharedPreferences.getInstance();
    final saved = prefs.getString(_prefsKey) ?? '';
    _addressCtrl.text = saved;
    // Auto-connect on launch if we already know the server address.
    if (saved.trim().isNotEmpty) {
      _connect();
    }
  }

  Future<void> _connect() async {
    final address = _addressCtrl.text.trim();
    if (address.isEmpty) return;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_prefsKey, address);
    await _broadcaster.start(address);
  }

  Future<void> _scanQr() async {
    final code = await Navigator.of(context).push<String>(
      MaterialPageRoute(builder: (_) => const QrScanScreen()),
    );
    if (code != null && code.trim().isNotEmpty) {
      _addressCtrl.text = code.trim();
      await _connect();
    }
  }

  Future<void> _stopAndExit() async {
    await _broadcaster.stop();
    if (mounted) {
      setState(() {
        _status = BroadcastStatus.idle;
        _message = '';
      });
    }
  }

  @override
  void dispose() {
    _broadcaster.dispose();
    _addressCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final connected = _status == BroadcastStatus.streaming ||
        _status == BroadcastStatus.connected;
    return Scaffold(
      body: SafeArea(
        child: Center(
          child: connected ? _buildStreamingView() : _buildSetupView(),
        ),
      ),
    );
  }

  // Minimal, text-only screen shown while connected. The app keeps streaming
  // in the background; press "Ngắt kết nối" to stop and return to setup.
  Widget _buildStreamingView() {
    return Padding(
      padding: const EdgeInsets.all(32),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          const Text(
            'Đã kết nối',
            style: TextStyle(fontSize: 22, fontWeight: FontWeight.w600),
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: 28),
          TextButton(
            onPressed: _stopAndExit,
            style: TextButton.styleFrom(
              foregroundColor: const Color(0xFFE5484D),
              padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
            ),
            child: const Text('Ngắt kết nối'),
          ),
        ],
      ),
    );
  }

  // First-run setup: enter the server address printed by the server console.
  Widget _buildSetupView() {
    final connecting = _status == BroadcastStatus.connecting;
    return Padding(
      padding: const EdgeInsets.all(28),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const Icon(Icons.videocam_outlined, size: 64, color: Color(0xFF30A46C)),
          const SizedBox(height: 16),
          const Text(
            'Camera WebRTC',
            style: TextStyle(fontSize: 24, fontWeight: FontWeight.bold),
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: 8),
          const Text(
            'Nhập địa chỉ máy chủ (hiện ở cửa sổ server), ví dụ 192.168.1.10:8080',
            style: TextStyle(fontSize: 13, color: Colors.white54),
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: 24),
          TextField(
            controller: _addressCtrl,
            keyboardType: TextInputType.url,
            autocorrect: false,
            enabled: !connecting,
            decoration: const InputDecoration(
              labelText: 'Địa chỉ máy chủ (IP:PORT)',
              hintText: '192.168.1.10:8080',
              prefixIcon: Icon(Icons.dns_outlined),
              border: OutlineInputBorder(),
            ),
            onSubmitted: (_) => _connect(),
          ),
          const SizedBox(height: 16),
          FilledButton.icon(
            onPressed: connecting ? null : _connect,
            icon: connecting
                ? const SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Icon(Icons.link),
            label: Text(connecting ? 'Đang kết nối…' : 'Kết nối'),
            style: FilledButton.styleFrom(
              padding: const EdgeInsets.symmetric(vertical: 15),
            ),
          ),
          const SizedBox(height: 12),
          OutlinedButton.icon(
            onPressed: connecting ? null : _scanQr,
            icon: const Icon(Icons.qr_code_scanner),
            label: const Text('Quét mã QR'),
            style: OutlinedButton.styleFrom(
              padding: const EdgeInsets.symmetric(vertical: 15),
            ),
          ),
          if (_status == BroadcastStatus.error) ...[
            const SizedBox(height: 14),
            Text(
              _message,
              style: const TextStyle(color: Color(0xFFE5484D), fontSize: 13),
              textAlign: TextAlign.center,
            ),
          ],
        ],
      ),
    );
  }
}

/// Full-screen camera QR scanner. Pops with the decoded string (the server
/// address, e.g. "192.168.1.10:8080").
class QrScanScreen extends StatefulWidget {
  const QrScanScreen({super.key});

  @override
  State<QrScanScreen> createState() => _QrScanScreenState();
}

class _QrScanScreenState extends State<QrScanScreen> {
  final _controller = MobileScannerController();
  bool _handled = false;

  void _onDetect(BarcodeCapture capture) {
    if (_handled) return;
    final value = capture.barcodes.isNotEmpty
        ? capture.barcodes.first.rawValue
        : null;
    if (value == null || value.trim().isEmpty) return;
    _handled = true;
    Navigator.of(context).pop(value);
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Quét mã QR')),
      body: Stack(
        alignment: Alignment.center,
        children: [
          MobileScanner(controller: _controller, onDetect: _onDetect),
          // Simple viewfinder frame.
          Container(
            width: 240,
            height: 240,
            decoration: BoxDecoration(
              border: Border.all(color: Colors.white, width: 3),
              borderRadius: BorderRadius.circular(16),
            ),
          ),
          const Positioned(
            bottom: 60,
            child: Text(
              'Hướng camera vào mã QR trên màn hình máy chủ',
              style: TextStyle(color: Colors.white, fontSize: 14),
              textAlign: TextAlign.center,
            ),
          ),
        ],
      ),
    );
  }
}

import 'package:flutter/material.dart';
import 'package:flutter_webrtc/flutter_webrtc.dart';
import 'package:mobile_scanner/mobile_scanner.dart';
import 'package:qr_flutter/qr_flutter.dart';

import '../config_store.dart';
import '../native_bridge.dart';
import '../remote/remote_client.dart';
import '../remote/remote_server.dart';

enum _Role { none, server, client }

/// Remote-control tab. Choose **Server** (this phone controls others and shows
/// a QR to connect) or **Client** (this phone is controlled). One phone can
/// only be one role at a time; switching roles tears the other down.
class RemotePage extends StatefulWidget {
  const RemotePage({super.key});

  @override
  State<RemotePage> createState() => _RemotePageState();
}

class _RemotePageState extends State<RemotePage> {
  final _config = ConfigStore();
  final _nameCtrl = TextEditingController();
  final _addressCtrl = TextEditingController();

  _Role _role = _Role.none;
  RemoteServer? _server;
  RemoteClient? _client;
  bool _ready = false;

  @override
  void initState() {
    super.initState();
    _init();
  }

  Future<void> _init() async {
    await _config.load();
    var name = _config.remoteName;
    if (name.isEmpty) name = _config.deviceName;
    if (name.isEmpty) name = await NativeBridge.instance.deviceName();
    _nameCtrl.text = name;
    _addressCtrl.text = _config.remoteServer;
    if (mounted) setState(() => _ready = true);
  }

  @override
  void dispose() {
    _server?.dispose();
    _client?.dispose();
    _nameCtrl.dispose();
    _addressCtrl.dispose();
    super.dispose();
  }

  Future<void> _enterServer() async {
    await _client?.disconnect();
    _client = null;
    final server = RemoteServer();
    _server = server;
    setState(() => _role = _Role.server);
    await server.start();
  }

  void _enterClient() {
    _server?.dispose();
    _server = null;
    _client ??= RemoteClient();
    setState(() => _role = _Role.client);
  }

  Future<void> _leaveRole() async {
    await _server?.stop();
    _server?.dispose();
    _server = null;
    await _client?.disconnect();
    _client = null;
    if (mounted) setState(() => _role = _Role.none);
  }

  void _toast(String msg, {bool error = false}) {
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(
        content: Text(msg),
        backgroundColor: error ? const Color(0xFFE5484D) : const Color(0xFF30A46C),
      ));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Điều khiển từ xa'),
        centerTitle: true,
        leading: _role == _Role.none
            ? null
            : IconButton(icon: const Icon(Icons.arrow_back), onPressed: _leaveRole),
      ),
      body: SafeArea(
        child: !_ready
            ? const Center(child: CircularProgressIndicator())
            : switch (_role) {
                _Role.none => _roleChooser(),
                _Role.server => _serverView(),
                _Role.client => _clientView(),
              },
      ),
    );
  }

  // --- Role chooser -------------------------------------------------------

  Widget _roleChooser() {
    return Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          const Icon(Icons.devices_other, size: 64, color: Color(0xFF30A46C)),
          const SizedBox(height: 12),
          const Text(
            'Chọn vai trò cho máy này',
            style: TextStyle(fontSize: 18, fontWeight: FontWeight.w600),
          ),
          const SizedBox(height: 24),
          _roleButton(
            icon: Icons.dns,
            color: const Color(0xFF30A46C),
            title: 'Server',
            subtitle: 'Máy này điều khiển các máy khác. Hiển thị QR để kết nối.',
            onTap: _enterServer,
          ),
          const SizedBox(height: 14),
          _roleButton(
            icon: Icons.smartphone,
            color: const Color(0xFF3B6EF0),
            title: 'Client',
            subtitle: 'Máy này bị điều khiển (chụp/quay/ghi âm) bởi máy Server.',
            onTap: _enterClient,
          ),
        ],
      ),
    );
  }

  Widget _roleButton({
    required IconData icon,
    required Color color,
    required String title,
    required String subtitle,
    required VoidCallback onTap,
  }) {
    return Material(
      color: color.withValues(alpha: 0.15),
      borderRadius: BorderRadius.circular(16),
      child: InkWell(
        borderRadius: BorderRadius.circular(16),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Row(
            children: [
              Icon(icon, color: color, size: 36),
              const SizedBox(width: 16),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(title,
                        style: TextStyle(
                            fontSize: 18, fontWeight: FontWeight.bold, color: color)),
                    const SizedBox(height: 4),
                    Text(subtitle,
                        style: const TextStyle(fontSize: 12, color: Colors.white70)),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  // --- Server view --------------------------------------------------------

  Widget _serverView() {
    final server = _server;
    if (server == null) return const SizedBox.shrink();
    return ListenableBuilder(
      listenable: server,
      builder: (context, _) {
        if (server.error != null) {
          final err = server.error!;
          server.error = null; // show once, don't spam on rebuilds
          WidgetsBinding.instance.addPostFrameCallback((_) {
            if (mounted) _toast(err, error: true);
          });
        }
        return ListView(
          padding: const EdgeInsets.all(16),
          children: [
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  children: [
                    if (server.address != null) ...[
                      Container(
                        padding: const EdgeInsets.all(10),
                        color: Colors.white,
                        child: QrImageView(
                          data: server.address!,
                          version: QrVersions.auto,
                          size: 180,
                        ),
                      ),
                      const SizedBox(height: 12),
                      SelectableText(
                        server.address!,
                        style: const TextStyle(
                            fontSize: 16, fontWeight: FontWeight.bold),
                      ),
                      const Text(
                        'Trên máy Client: chọn Client → quét QR hoặc nhập địa chỉ này.',
                        style: TextStyle(fontSize: 12, color: Colors.white54),
                        textAlign: TextAlign.center,
                      ),
                    ] else
                      const Padding(
                        padding: EdgeInsets.all(8),
                        child: Text(
                          'Không lấy được địa chỉ IP LAN. Hãy bật Wi-Fi và thử lại.',
                          textAlign: TextAlign.center,
                          style: TextStyle(color: Color(0xFFE5484D)),
                        ),
                      ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                const Text('Thiết bị đã kết nối',
                    style: TextStyle(fontSize: 15, fontWeight: FontWeight.bold)),
                const Spacer(),
                Text('${server.clients.length}',
                    style: const TextStyle(color: Colors.white54)),
              ],
            ),
            const SizedBox(height: 8),
            if (server.clients.isEmpty)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 24),
                child: Text(
                  'Chưa có thiết bị nào kết nối.',
                  textAlign: TextAlign.center,
                  style: TextStyle(color: Colors.white54),
                ),
              )
            else
              ...server.clients.map((c) => Card(
                    child: ListTile(
                      leading: const Icon(Icons.smartphone, color: Color(0xFF30A46C)),
                      title: Text(c.name),
                      subtitle: Text(
                        [
                          if (c.recording) '● đang quay',
                          if (c.audioRecording) '● đang ghi âm',
                          'cam ${c.facing == 'back' ? 'sau' : 'trước'}',
                        ].join('  ·  '),
                        style: const TextStyle(fontSize: 12),
                      ),
                      trailing: const Icon(Icons.chevron_right),
                      onTap: () => Navigator.of(context).push(
                        MaterialPageRoute(
                          builder: (_) =>
                              _ClientControlScreen(server: server, clientId: c.id),
                        ),
                      ),
                    ),
                  )),
          ],
        );
      },
    );
  }

  // --- Client view --------------------------------------------------------

  Widget _clientView() {
    final client = _client;
    if (client == null) return const SizedBox.shrink();
    return ListenableBuilder(
      listenable: client,
      builder: (context, _) {
        final connecting = client.status == RemoteClientStatus.connecting;
        final connected = client.connected;
        return ListView(
          padding: const EdgeInsets.all(20),
          children: [
            const SizedBox(height: 8),
            Icon(
              connected ? Icons.link : Icons.smartphone,
              size: 56,
              color: connected ? const Color(0xFF30A46C) : Colors.white54,
            ),
            const SizedBox(height: 16),
            TextField(
              controller: _nameCtrl,
              enabled: !connected && !connecting,
              decoration: const InputDecoration(
                labelText: 'Tên thiết bị',
                prefixIcon: Icon(Icons.badge_outlined),
                border: OutlineInputBorder(),
              ),
              onChanged: (v) => _config.setRemoteName(v),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _addressCtrl,
              enabled: !connected && !connecting,
              keyboardType: TextInputType.url,
              autocorrect: false,
              decoration: InputDecoration(
                labelText: 'Địa chỉ máy Server (IP:PORT)',
                hintText: '192.168.1.10:8080',
                prefixIcon: const Icon(Icons.dns_outlined),
                border: const OutlineInputBorder(),
                suffixIcon: IconButton(
                  icon: const Icon(Icons.qr_code_scanner),
                  onPressed: (connected || connecting) ? null : _scanQr,
                ),
              ),
            ),
            const SizedBox(height: 16),
            if (!connected)
              FilledButton.icon(
                onPressed: connecting ? null : _connectClient,
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
              )
            else
              OutlinedButton.icon(
                onPressed: () => client.disconnect(),
                icon: const Icon(Icons.link_off),
                label: const Text('Ngắt kết nối'),
                style: OutlinedButton.styleFrom(
                  foregroundColor: const Color(0xFFE5484D),
                  padding: const EdgeInsets.symmetric(vertical: 15),
                ),
              ),
            const SizedBox(height: 16),
            if (connected)
              Column(
                children: [
                  const Text('Đang chờ lệnh từ máy Server.',
                      style: TextStyle(color: Color(0xFF30A46C))),
                  if (client.recording)
                    const Text('● Đang quay', style: TextStyle(color: Color(0xFFE5484D))),
                  if (client.audioRecording)
                    const Text('● Đang ghi âm', style: TextStyle(color: Color(0xFFE5484D))),
                ],
              ),
            if (client.message.isNotEmpty && !connected)
              Text(
                client.message,
                textAlign: TextAlign.center,
                style: TextStyle(
                  color: client.status == RemoteClientStatus.error
                      ? const Color(0xFFE5484D)
                      : Colors.white54,
                ),
              ),
          ],
        );
      },
    );
  }

  Future<void> _connectClient() async {
    final address = _addressCtrl.text.trim();
    final name = _nameCtrl.text.trim();
    if (address.isEmpty) {
      _toast('Hãy nhập hoặc quét địa chỉ máy Server.', error: true);
      return;
    }
    await _config.setRemoteServer(address);
    await _config.setRemoteName(name);
    await _client?.connect(address, name.isEmpty ? 'Client' : name);
  }

  Future<void> _scanQr() async {
    final code = await Navigator.of(context).push<String>(
      MaterialPageRoute(builder: (_) => const _QrScanScreen()),
    );
    if (code != null && code.trim().isNotEmpty) {
      _addressCtrl.text = code.trim();
      await _connectClient();
    }
  }
}

/// Command panel for one client, plus its live camera view.
class _ClientControlScreen extends StatelessWidget {
  const _ClientControlScreen({required this.server, required this.clientId});

  final RemoteServer server;
  final int clientId;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: server,
      builder: (context, _) {
        RemoteClientConn? conn;
        for (final c in server.clients) {
          if (c.id == clientId) conn = c;
        }
        if (conn == null) {
          // Client disconnected while we were controlling it.
          WidgetsBinding.instance.addPostFrameCallback((_) {
            if (Navigator.of(context).canPop()) Navigator.of(context).pop();
          });
          return const Scaffold(
            body: Center(child: Text('Thiết bị đã ngắt kết nối.')),
          );
        }
        final c = conn;
        final viewing = server.viewingClientId == clientId;
        return Scaffold(
          appBar: AppBar(title: Text(c.name)),
          body: ListView(
            padding: const EdgeInsets.all(16),
            children: [
              // Live view area.
              AspectRatio(
                aspectRatio: 16 / 9,
                child: Container(
                  decoration: BoxDecoration(
                    color: Colors.black,
                    borderRadius: BorderRadius.circular(12),
                  ),
                  clipBehavior: Clip.antiAlias,
                  child: viewing && server.hasLiveView
                      ? RTCVideoView(server.renderer, mirror: c.facing == 'front')
                      : const Center(
                          child: Text('Chưa xem trực tiếp',
                              style: TextStyle(color: Colors.white38)),
                        ),
                ),
              ),
              const SizedBox(height: 12),
              FilledButton.icon(
                onPressed: () => viewing
                    ? server.stopLiveView()
                    : server.startLiveView(clientId),
                icon: Icon(viewing ? Icons.videocam_off : Icons.live_tv),
                label: Text(viewing ? 'Dừng xem trực tiếp' : 'Xem trực tiếp'),
              ),
              const SizedBox(height: 16),
              GridView.count(
                crossAxisCount: 2,
                shrinkWrap: true,
                physics: const NeverScrollableScrollPhysics(),
                mainAxisSpacing: 10,
                crossAxisSpacing: 10,
                childAspectRatio: 2.4,
                children: [
                  _cmd(Icons.photo_camera, 'Chụp ảnh', const Color(0xFF30A46C),
                      () => server.sendCommand(clientId, 'photo')),
                  _cmd(
                    c.recording ? Icons.stop : Icons.videocam,
                    c.recording ? 'Dừng quay' : 'Quay video',
                    c.recording ? const Color(0xFFE5484D) : const Color(0xFF3B6EF0),
                    () => server.sendCommand(
                        clientId, c.recording ? 'video-stop' : 'video-start'),
                  ),
                  _cmd(
                    c.audioRecording ? Icons.stop : Icons.mic,
                    c.audioRecording ? 'Dừng ghi âm' : 'Ghi âm',
                    c.audioRecording ? const Color(0xFFE5484D) : const Color(0xFF8E4EC6),
                    () => server.sendCommand(
                        clientId, c.audioRecording ? 'audio-stop' : 'audio-start'),
                  ),
                  _cmd(Icons.cameraswitch, 'Đổi camera', const Color(0xFFF5A623),
                      () => server.sendCommand(clientId, 'switch-camera')),
                  _cmd(Icons.center_focus_strong, 'Lấy nét', const Color(0xFF0EA5E9),
                      () => server.sendCommand(clientId, 'focus')),
                ],
              ),
              const SizedBox(height: 16),
              if (c.lastMessage.isNotEmpty)
                Container(
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: Colors.white10,
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: Row(
                    children: [
                      const Icon(Icons.info_outline, size: 18, color: Colors.white54),
                      const SizedBox(width: 8),
                      Expanded(child: Text(c.lastMessage,
                          style: const TextStyle(fontSize: 13))),
                    ],
                  ),
                ),
            ],
          ),
        );
      },
    );
  }

  Widget _cmd(IconData icon, String label, Color color, VoidCallback onTap) {
    return Material(
      color: color,
      borderRadius: BorderRadius.circular(14),
      child: InkWell(
        borderRadius: BorderRadius.circular(14),
        onTap: onTap,
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(icon, color: Colors.white, size: 22),
            const SizedBox(width: 8),
            Flexible(
              child: Text(label,
                  style: const TextStyle(
                      color: Colors.white, fontWeight: FontWeight.w600),
                  overflow: TextOverflow.ellipsis),
            ),
          ],
        ),
      ),
    );
  }
}

/// Full-screen QR scanner used by the client to read the server address.
class _QrScanScreen extends StatefulWidget {
  const _QrScanScreen();

  @override
  State<_QrScanScreen> createState() => _QrScanScreenState();
}

class _QrScanScreenState extends State<_QrScanScreen> {
  final _controller = MobileScannerController();
  bool _handled = false;

  void _onDetect(BarcodeCapture capture) {
    if (_handled) return;
    final value =
        capture.barcodes.isNotEmpty ? capture.barcodes.first.rawValue : null;
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
      appBar: AppBar(title: const Text('Quét QR máy Server')),
      body: Stack(
        alignment: Alignment.center,
        children: [
          MobileScanner(controller: _controller, onDetect: _onDetect),
          Container(
            width: 240,
            height: 240,
            decoration: BoxDecoration(
              border: Border.all(color: Colors.white, width: 3),
              borderRadius: BorderRadius.circular(16),
            ),
          ),
        ],
      ),
    );
  }
}

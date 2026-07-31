import 'package:flutter/material.dart';
import 'package:qr_flutter/qr_flutter.dart';

import 'audio.dart';
import 'config_store.dart';
import 'connection.dart';
import 'foreground.dart';
import 'theme_controller.dart';
import 'widgets.dart';

/// Speaker = host. Runs the WS server, shows its address/QR, receives play
/// commands + live audio, and can talk back.
class SpeakerScreen extends StatefulWidget {
  final ConfigStore store;
  final ThemeController theme;
  const SpeakerScreen({super.key, required this.store, required this.theme});

  @override
  State<SpeakerScreen> createState() => _SpeakerScreenState();
}

class _SpeakerScreenState extends State<SpeakerScreen> {
  final _conn = WalkieConnection();
  final _engine = AudioEngine();

  bool _connected = false;
  bool _talking = false;
  bool _remoteTalking = false;
  List<String> _addresses = [];
  final Map<String, String> _soundPaths = {}; // id -> local file path

  @override
  void initState() {
    super.initState();
    _setup();
  }

  Future<void> _setup() async {
    // Reuse audio cached on disk from previous sessions (saves transfer).
    await _loadCache();

    _conn.onConnectionChanged = (c) {
      if (!mounted) return;
      setState(() {
        _connected = c;
        if (!c) {
          _remoteTalking = false;
          _talking = false;
        }
      });
      if (c) {
        ForegroundService.start();
        // Speaker answers; remoter is the offerer.
        _engine.initLive(offerer: false, sendSignal: _conn.sendSignal);
        // Tell the remoter which clips we already have so it only sends new ones.
        _conn.sendHave(_soundPaths.keys.toList());
      } else {
        _engine.closeLive();
      }
    };
    _conn.onSignal = _engine.handleSignal;
    _conn.onRemoteTalk = (on) {
      if (mounted) setState(() => _remoteTalking = on);
    };
    _conn.onSound = (id, ext, bytes) async {
      final f = await widget.store.soundFile('$id.$ext');
      await f.writeAsBytes(bytes);
      _soundPaths[id] = f.path;
    };
    _conn.onPlay = (id) {
      final p = _soundPaths[id];
      if (p != null) {
        _engine.playClip(p);
      } else {
        _conn.requestSound(id); // ask remoter to (re)send this clip
      }
    };
    _conn.onClearAudio = _clearAudio;

    final ips = await WalkieConnection.localIps();
    try {
      await _conn.host();
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('Không mở được phòng: $e')));
      }
    }
    if (mounted) {
      setState(() => _addresses = ips.map((ip) => '$ip:$kWalkiePort').toList());
    }
  }

  Future<void> _loadCache() async {
    final names = await widget.store.cachedSoundNames();
    final dir = await widget.store.soundsDir();
    for (final n in names) {
      final id = n.contains('.') ? n.substring(0, n.lastIndexOf('.')) : n;
      _soundPaths[id] = '${dir.path}/$n';
    }
    if (mounted) setState(() {});
  }

  Future<void> _confirmClearAudio() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('Xoá bộ nhớ audio?'),
        content: Text(
            'Xoá ${_soundPaths.length} clip đã lưu trên máy này. Remoter sẽ gửi lại khi cần.'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('Huỷ')),
          FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('Xoá')),
        ],
      ),
    );
    if (ok == true) await _clearAudio();
  }

  Future<void> _clearAudio() async {
    await widget.store.clearSounds();
    _soundPaths.clear();
    if (mounted) {
      setState(() {});
      ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Đã xoá bộ nhớ audio.')));
    }
  }

  Future<void> _toggleTalk() async {
    if (!_talking) {
      final ok = await _engine.startTalk();
      if (!ok) {
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
              const SnackBar(content: Text('Cần quyền micro để nói.')));
        }
        return;
      }
      _conn.sendTalk(true);
      setState(() => _talking = true);
    } else {
      await _engine.stopTalk();
      _conn.sendTalk(false);
      setState(() => _talking = false);
    }
  }

  @override
  void dispose() {
    _engine.dispose();
    _conn.close();
    ForegroundService.stop();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Speaker'),
        actions: [
          IconButton(
            tooltip: 'Xoá bộ nhớ audio (${_soundPaths.length} clip)',
            icon: const Icon(Icons.delete_sweep_outlined),
            onPressed: _soundPaths.isEmpty ? null : _confirmClearAudio,
          ),
          ThemeToggleButton(theme: widget.theme),
        ],
      ),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: _connected ? _connectedView() : _waitingView(),
        ),
      ),
    );
  }

  Widget _waitingView() {
    final address = _addresses.isNotEmpty ? _addresses.first : null;
    return Center(
      child: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Text('Chờ Remoter kết nối…',
                style: TextStyle(fontSize: 18, fontWeight: FontWeight.w600)),
            const SizedBox(height: 6),
            Text('Mở app máy kia → chọn Remoter → quét mã QR bên dưới.',
                textAlign: TextAlign.center,
                style: TextStyle(color: Theme.of(context).hintColor)),
            const SizedBox(height: 20),
            if (address != null) ...[
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Colors.white,
                  borderRadius: BorderRadius.circular(12),
                ),
                child: QrImageView(data: address, size: 220),
              ),
              const SizedBox(height: 16),
              const Text('Địa chỉ (nhập tay nếu cần):'),
              const SizedBox(height: 6),
              for (final a in _addresses)
                SelectableText(a,
                    style: const TextStyle(
                        fontSize: 16, fontWeight: FontWeight.w600)),
            ] else
              Text('Không tìm thấy IP. Hãy bật Wi-Fi và thử lại.',
                  style: TextStyle(color: Theme.of(context).colorScheme.error)),
            const SizedBox(height: 24),
            const SizedBox(
                width: 22,
                height: 22,
                child: CircularProgressIndicator(strokeWidth: 2)),
          ],
        ),
      ),
    );
  }

  Widget _connectedView() {
    return Column(
      children: [
        const SizedBox(height: 8),
        Icon(Icons.link, size: 40, color: Theme.of(context).colorScheme.primary),
        const SizedBox(height: 8),
        const Text('Đã kết nối với Remoter',
            style: TextStyle(fontSize: 18, fontWeight: FontWeight.w600)),
        const SizedBox(height: 20),
        ReceivingBadge(active: _remoteTalking),
        const Spacer(),
        TalkToggle(talking: _talking, enabled: true, onPressed: _toggleTalk),
        const SizedBox(height: 12),
        OutlinedButton.icon(
          onPressed: () => Navigator.pop(context),
          icon: const Icon(Icons.stop_circle_outlined),
          label: const Text('Ngắt kết nối'),
          style: OutlinedButton.styleFrom(
            foregroundColor: Theme.of(context).colorScheme.error,
          ),
        ),
        const SizedBox(height: 8),
      ],
    );
  }
}

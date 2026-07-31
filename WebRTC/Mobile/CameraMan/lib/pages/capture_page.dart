import 'dart:async';

import 'package:flutter/material.dart';

import '../config_store.dart';
import '../native_bridge.dart';

/// Main tab: the same four actions the home-screen widget exposes — photo and
/// video for each of the front and back cameras. Video buttons toggle (tap to
/// start recording, tap again to stop). Capture runs natively and completes
/// out-of-process, so results arrive back over [NativeBridge.events].
class CapturePage extends StatefulWidget {
  const CapturePage({super.key});

  @override
  State<CapturePage> createState() => _CapturePageState();
}

class _CapturePageState extends State<CapturePage> {
  final _bridge = NativeBridge.instance;
  final _config = ConfigStore();
  StreamSubscription<CaptureEvent>? _sub;

  bool _recording = false;
  bool _motion = false;
  String _motionLens = 'back';

  @override
  void initState() {
    super.initState();
    _syncState();
    _sub = _bridge.events.listen(_onEvent);
  }

  Future<void> _syncState() async {
    final rec = await _bridge.isRecording();
    final mot = await _bridge.isMotionRunning();
    await _config.load();
    if (!mounted) return;
    setState(() {
      _recording = rec;
      _motion = mot;
      _motionLens = _config.motionLens;
    });
  }

  Future<void> _reloadMotionLens() async {
    await _config.load();
    if (mounted) setState(() => _motionLens = _config.motionLens);
  }

  void _onEvent(CaptureEvent e) {
    if (!mounted) return;
    switch (e.type) {
      case CaptureEventType.recording:
        setState(() => _recording = e.value);
        break;
      case CaptureEventType.motion:
        setState(() => _motion = e.value);
        if (e.value) _reloadMotionLens();
        break;
      case CaptureEventType.captured:
        final label = e.mediaType == 'video' ? 'video' : 'ảnh';
        _toast('Đã lưu $label ✓');
        break;
      case CaptureEventType.error:
        _toast(e.message ?? 'Có lỗi xảy ra', error: true);
        break;
      case CaptureEventType.busy:
        _toast(e.message ?? 'Đang bận', error: true);
        break;
      case CaptureEventType.unknown:
        break;
    }
  }

  void _toast(String msg, {bool error = false}) {
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(
        content: Text(msg),
        backgroundColor: error ? const Color(0xFFE5484D) : const Color(0xFF30A46C),
        duration: const Duration(seconds: 2),
      ));
  }

  @override
  void dispose() {
    _sub?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('CameraMan'),
        centerTitle: true,
      ),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            children: [
              if (_motion) _banner(
                icon: Icons.sensors,
                color: const Color(0xFFF5A623),
                text: 'Đang phát hiện chuyển động — camera '
                    '${_motionLens == 'front' ? 'trước' : 'sau'}',
              ),
              if (_recording) _banner(
                icon: Icons.fiber_manual_record,
                color: const Color(0xFFE5484D),
                text: 'Đang ghi hình…',
              ),
              const SizedBox(height: 8),
              Expanded(
                child: Column(
                  children: [
                    _cameraSection(
                      title: 'Camera trước',
                      lens: 'front',
                      color: const Color(0xFF30A46C),
                    ),
                    const SizedBox(height: 16),
                    _cameraSection(
                      title: 'Camera sau',
                      lens: 'back',
                      color: const Color(0xFF3B6EF0),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 8),
              // 5th button: motion-detection toggle (matches the combo widget).
              SizedBox(
                width: double.infinity,
                child: FilledButton.icon(
                  onPressed: () {
                    if (_motion) {
                      _bridge.stopMotion();
                    } else {
                      _bridge.startMotion();
                    }
                  },
                  icon: Icon(_motion ? Icons.sensors_off : Icons.sensors),
                  label: Text(_motion
                      ? 'Tắt phát hiện chuyển động'
                      : 'Bật phát hiện chuyển động'),
                  style: FilledButton.styleFrom(
                    backgroundColor:
                        _motion ? const Color(0xFFF5A623) : const Color(0xFF6B4EA0),
                    foregroundColor: Colors.white,
                    padding: const EdgeInsets.symmetric(vertical: 14),
                  ),
                ),
              ),
              const SizedBox(height: 8),
              const Text(
                'Mẹo: giữ và thả widget CameraMan ra màn hình chính để chụp/quay nhanh mà không cần mở app.',
                style: TextStyle(fontSize: 12, color: Colors.white54),
                textAlign: TextAlign.center,
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _banner({required IconData icon, required Color color, required String text}) {
    return Container(
      width: double.infinity,
      margin: const EdgeInsets.only(bottom: 8),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.15),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: color.withValues(alpha: 0.5)),
      ),
      child: Row(
        children: [
          Icon(icon, color: color, size: 18),
          const SizedBox(width: 8),
          Expanded(child: Text(text, style: TextStyle(color: color, fontSize: 13))),
        ],
      ),
    );
  }

  Widget _cameraSection({
    required String title,
    required String lens,
    required Color color,
  }) {
    return Expanded(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title, style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600)),
          const SizedBox(height: 8),
          Expanded(
            child: Row(
              children: [
                Expanded(
                  child: _actionButton(
                    icon: Icons.photo_camera,
                    label: 'Chụp ảnh',
                    color: color,
                    onTap: _motion ? null : () => _bridge.capturePhoto(lens),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: _actionButton(
                    icon: _recording ? Icons.stop : Icons.videocam,
                    label: _recording ? 'Dừng quay' : 'Quay video',
                    color: _recording ? const Color(0xFFE5484D) : color,
                    onTap: _motion ? null : () => _bridge.toggleVideo(lens),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _actionButton({
    required IconData icon,
    required String label,
    required Color color,
    required VoidCallback? onTap,
  }) {
    final enabled = onTap != null;
    return Material(
      color: enabled ? color : color.withValues(alpha: 0.25),
      borderRadius: BorderRadius.circular(18),
      child: InkWell(
        borderRadius: BorderRadius.circular(18),
        onTap: onTap,
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(icon, size: 40, color: Colors.white),
            const SizedBox(height: 8),
            Text(
              label,
              style: const TextStyle(
                color: Colors.white,
                fontSize: 15,
                fontWeight: FontWeight.w600,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

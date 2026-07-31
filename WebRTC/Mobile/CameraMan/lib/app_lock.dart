import 'dart:io';

import 'package:flutter/material.dart';
import 'package:path_provider/path_provider.dart';

import 'config_store.dart';
import 'native_bridge.dart';

/// Wraps the app UI with a PIN gate.
///
/// The lock is a full-screen overlay laid *on top of* the running UI — it never
/// unmounts the app, so anything already in progress (native recording, motion
/// detection, the remote server/client, widget captures) keeps running while
/// the screen is locked. The gate re-arms whenever the app is backgrounded or
/// the screen turns off, and on cold start.
///
/// Home-screen widget taps go straight to the native service and never open
/// this UI, so they are never blocked by the PIN.
class AppLock extends StatefulWidget {
  const AppLock({super.key, required this.child});

  final Widget child;

  @override
  State<AppLock> createState() => _AppLockState();
}

class _AppLockState extends State<AppLock> with WidgetsBindingObserver {
  final _config = ConfigStore();
  bool _ready = false;
  bool _lockEnabled = false;
  bool _locked = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _load();
  }

  Future<void> _load() async {
    await _config.load();
    _lockEnabled = _config.lockEnabled;
    _locked = _lockEnabled; // require the PIN on cold start
    if (mounted) setState(() => _ready = true);
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Re-arm on the app leaving the foreground (screen off / recents / home).
    // 'inactive' is intentionally ignored so transient system dialogs don't
    // trigger a lock.
    if (state == AppLifecycleState.paused || state == AppLifecycleState.hidden) {
      _refreshAndLock();
    }
  }

  Future<void> _refreshAndLock() async {
    await _config.load();
    final enabled = _config.lockEnabled;
    if (mounted) {
      setState(() {
        _lockEnabled = enabled;
        if (enabled) _locked = true;
      });
    }
  }

  /// Wipes all captured media (keeps every setting) and clears the PIN so the
  /// user regains access. Triggered by "Quên mã PIN?".
  Future<void> _forgotPin() async {
    try {
      await NativeBridge.instance.wipeMedia();
    } catch (_) {}
    // Remote-control captures live in a separate folder.
    try {
      final base = await getExternalStorageDirectory();
      if (base != null) {
        final remote = Directory('${base.path}/Remote');
        if (await remote.exists()) await remote.delete(recursive: true);
      }
    } catch (_) {}
    await _config.disableLock();
    if (mounted) {
      setState(() {
        _lockEnabled = false;
        _locked = false;
      });
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Stack(
      children: [
        widget.child,
        if (_ready && _lockEnabled && _locked)
          Positioned.fill(
            child: _PinLockView(
              config: _config,
              onUnlocked: () => setState(() => _locked = false),
              onForgot: _forgotPin,
            ),
          ),
      ],
    );
  }
}

class _PinLockView extends StatefulWidget {
  const _PinLockView({
    required this.config,
    required this.onUnlocked,
    required this.onForgot,
  });

  final ConfigStore config;
  final VoidCallback onUnlocked;
  final Future<void> Function() onForgot;

  @override
  State<_PinLockView> createState() => _PinLockViewState();
}

class _PinLockViewState extends State<_PinLockView> {
  String _entered = '';
  bool _error = false;

  void _press(String digit) {
    if (_entered.length >= 12) return;
    setState(() {
      _entered += digit;
      _error = false;
    });
  }

  void _backspace() {
    if (_entered.isEmpty) return;
    setState(() => _entered = _entered.substring(0, _entered.length - 1));
  }

  void _submit() {
    if (widget.config.verifyPassword(_entered)) {
      widget.onUnlocked();
    } else {
      setState(() {
        _error = true;
        _entered = '';
      });
    }
  }

  Future<void> _forgot() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('Quên mã PIN?'),
        content: const Text(
          'Toàn bộ ảnh/video/ghi âm đã lưu sẽ bị xoá để mở khoá. '
          'Các cài đặt được giữ nguyên. Tiếp tục?',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Huỷ'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            style: TextButton.styleFrom(foregroundColor: const Color(0xFFE5484D)),
            child: const Text('Xoá & mở khoá'),
          ),
        ],
      ),
    );
    if (ok == true) await widget.onForgot();
  }

  @override
  Widget build(BuildContext context) {
    final accent = Theme.of(context).colorScheme.primary;
    return Material(
      color: const Color(0xFF0F1115),
      child: SafeArea(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.lock, size: 56, color: accent),
            const SizedBox(height: 12),
            const Text(
              'Nhập mã PIN',
              style: TextStyle(fontSize: 18, fontWeight: FontWeight.w600),
            ),
            const SizedBox(height: 20),
            // PIN dots.
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: List.generate(
                _entered.isEmpty ? 1 : _entered.length,
                (_) => Container(
                  width: 14,
                  height: 14,
                  margin: const EdgeInsets.symmetric(horizontal: 6),
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: _entered.isEmpty ? Colors.white24 : accent,
                  ),
                ),
              ),
            ),
            SizedBox(
              height: 24,
              child: _error
                  ? const Text('Mã PIN không đúng',
                      style: TextStyle(color: Color(0xFFE5484D)))
                  : null,
            ),
            const SizedBox(height: 8),
            _keypad(accent),
            const SizedBox(height: 8),
            TextButton(
              onPressed: _forgot,
              child: const Text('Quên mã PIN?',
                  style: TextStyle(color: Colors.white54)),
            ),
          ],
        ),
      ),
    );
  }

  Widget _keypad(Color accent) {
    Widget key(String label, {VoidCallback? onTap, IconData? icon, Color? color}) {
      return Padding(
        padding: const EdgeInsets.all(8),
        child: SizedBox(
          width: 72,
          height: 72,
          child: Material(
            color: color ?? Colors.white10,
            shape: const CircleBorder(),
            child: InkWell(
              customBorder: const CircleBorder(),
              onTap: onTap,
              child: Center(
                child: icon != null
                    ? Icon(icon, color: Colors.white, size: 26)
                    : Text(label,
                        style: const TextStyle(
                            fontSize: 24, fontWeight: FontWeight.w500)),
              ),
            ),
          ),
        ),
      );
    }

    return Column(
      children: [
        for (final row in const [
          ['1', '2', '3'],
          ['4', '5', '6'],
          ['7', '8', '9'],
        ])
          Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [for (final d in row) key(d, onTap: () => _press(d))],
          ),
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            key('', icon: Icons.backspace_outlined, onTap: _backspace),
            key('0', onTap: () => _press('0')),
            key('', icon: Icons.check, onTap: _submit, color: accent),
          ],
        ),
      ],
    );
  }
}

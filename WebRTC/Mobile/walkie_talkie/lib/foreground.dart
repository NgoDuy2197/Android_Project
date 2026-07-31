import 'package:flutter/services.dart';

/// Thin wrapper around the native audio foreground service, which keeps the
/// mic/playback alive when the screen is off or the app is backgrounded.
class ForegroundService {
  static const _channel = MethodChannel('walkie/foreground');
  static bool _running = false;

  static Future<void> start() async {
    if (_running) return;
    try {
      await _channel.invokeMethod('start');
      _running = true;
    } on PlatformException {
      // ignore
    } on MissingPluginException {
      // ignore (non-Android)
    }
  }

  static Future<void> stop() async {
    if (!_running) return;
    try {
      await _channel.invokeMethod('stop');
    } catch (_) {}
    _running = false;
  }
}

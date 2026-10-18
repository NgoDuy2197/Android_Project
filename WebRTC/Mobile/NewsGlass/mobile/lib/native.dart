import 'package:flutter/services.dart';

/// Bridge to the native fetcher (WorkManager), notifications and widget.
class Native {
  static const _ch = MethodChannel('news/native');

  /// Fetches every enabled feed now. Returns the number of new items, or -1 on error.
  static Future<int> fetchNow() async {
    try {
      return await _ch.invokeMethod<int>('fetchNow') ?? 0;
    } catch (_) {
      return -1;
    }
  }

  static Future<void> markRead(List<String> ids) => _ch.invokeMethod('markRead', {'ids': ids});

  /// Re-reads config.json: reschedules the periodic fetch, redraws the widget.
  static Future<void> configChanged() => _ch.invokeMethod('configChanged');

  /// {exactAlarm, battery} → granted?
  static Future<Map<String, bool>> permissions() async =>
      (await _ch.invokeMapMethod<String, bool>('permissions')) ?? {};
  static Future<void> openPermission(String which) => _ch.invokeMethod('openPermission', {'which': which});

  /// Shell watchdog (keepalive.sh) via Shizuku: {installed, running, permitted, watchdog}.
  static Future<Map<String, dynamic>> keepAliveStatus() async =>
      (await _ch.invokeMapMethod<String, dynamic>('keepAliveStatus')) ?? {};
  static Future<void> shizukuRequest() => _ch.invokeMethod('shizukuRequest');
  static Future<bool> keepAliveStart() async => await _ch.invokeMethod<bool>('keepAliveStart') ?? false;
  static Future<bool> keepAliveStop() async => await _ch.invokeMethod<bool>('keepAliveStop') ?? false;

  static Future<bool> pinWidget() async => await _ch.invokeMethod<bool>('pinWidget') ?? false;
}

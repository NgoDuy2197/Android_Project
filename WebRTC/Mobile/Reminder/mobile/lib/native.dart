import 'package:flutter/services.dart';

/// Bridge to the native alarm scheduler, recorder and permission screens.
class Native {
  static const _ch = MethodChannel('reminder/native');

  /// Re-reads reminders.json, computes next times and (re)schedules alarms.
  /// Returns how many reminders were missed because the app had been killed.
  static Future<int> reschedule() async => await _ch.invokeMethod<int>('reschedule') ?? 0;

  /// Rings a reminder right away (for "Thử chuông").
  static Future<void> test(String id) => _ch.invokeMethod('test', {'id': id});
  static Future<void> stopRinging() => _ch.invokeMethod('stopRinging');

  /// {notifications, exactAlarm, fullScreen} → granted?
  static Future<Map<String, bool>> permissions() async =>
      (await _ch.invokeMapMethod<String, bool>('permissions')) ?? {};
  static Future<void> openPermission(String which) => _ch.invokeMethod('openPermission', {'which': which});

  /// Shell watchdog (keepalive.sh) via Shizuku: {installed, running, permitted, watchdog}.
  static Future<Map<String, dynamic>> keepAliveStatus() async =>
      (await _ch.invokeMapMethod<String, dynamic>('keepAliveStatus')) ?? {};
  static Future<void> shizukuRequest() => _ch.invokeMethod('shizukuRequest');
  static Future<bool> keepAliveStart() async => await _ch.invokeMethod<bool>('keepAliveStart') ?? false;
  static Future<bool> keepAliveStop() async => await _ch.invokeMethod<bool>('keepAliveStop') ?? false;

  static Future<bool> startRecording(String path) async =>
      await _ch.invokeMethod<bool>('startRecording', {'path': path}) ?? false;

  /// Returns the recording length in ms.
  static Future<int> stopRecording() async => await _ch.invokeMethod<int>('stopRecording') ?? 0;
  static Future<void> play(String path) => _ch.invokeMethod('play', {'path': path});
  static Future<void> stopPlay() => _ch.invokeMethod('stopPlay');
}

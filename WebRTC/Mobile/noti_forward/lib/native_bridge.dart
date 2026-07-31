import 'dart:async';
import 'dart:convert';

import 'package:flutter/services.dart';

/// Channels shared with the native side (see Const.kt).
const methodChannel = MethodChannel('noti_forward/native');
const eventChannel = EventChannel('noti_forward/events');

class InstalledApp {
  InstalledApp({
    required this.packageName,
    required this.label,
    required this.isSystem,
    this.iconBytes,
  });

  final String packageName;
  final String label;
  final bool isSystem;
  final Uint8List? iconBytes;

  factory InstalledApp.fromMap(Map<dynamic, dynamic> m) {
    Uint8List? icon;
    final raw = m['icon'];
    if (raw is String && raw.isNotEmpty) {
      try {
        icon = base64Decode(raw);
      } catch (_) {}
    }
    return InstalledApp(
      packageName: (m['package'] ?? '').toString(),
      label: (m['label'] ?? '').toString(),
      isSystem: m['isSystem'] == true,
      iconBytes: icon,
    );
  }
}

class NativeBridge {
  static Future<bool> isPermissionGranted() async {
    try {
      return await methodChannel.invokeMethod<bool>('isPermissionGranted') ??
          false;
    } on PlatformException {
      return false;
    }
  }

  static Future<void> requestPermission() async {
    try {
      await methodChannel.invokeMethod('requestPermission');
    } on PlatformException {
      // Settings may still open on some ROMs.
    }
  }

  static Future<void> openAppDetails() async {
    try {
      await methodChannel.invokeMethod('openAppDetails');
    } on PlatformException {
      // Ignore if OEM blocks the intent.
    }
  }

  static Future<bool> isIgnoringBattery() async {
    try {
      return await methodChannel.invokeMethod<bool>('isIgnoringBattery') ??
          false;
    } on PlatformException {
      return false;
    }
  }

  static Future<void> requestIgnoreBattery() async {
    try {
      await methodChannel.invokeMethod('requestIgnoreBattery');
    } on PlatformException {
      // Ignore if the battery settings intent is unavailable.
    }
  }

  static Future<void> applyBackground(bool enabled) async {
    try {
      await methodChannel
          .invokeMethod('applyBackground', {'enabled': enabled});
    } on PlatformException {
      // Keep-alive may be restricted in background.
    }
  }

  static Future<List<InstalledApp>> listInstalledApps({
    bool includeIcons = true,
  }) async {
    try {
      final raw = await methodChannel.invokeMethod<List<dynamic>>(
        'listInstalledApps',
        {'includeIcons': includeIcons},
      );
      return (raw ?? [])
          .whereType<Map>()
          .map((m) => InstalledApp.fromMap(m))
          .where((a) => a.packageName.isNotEmpty)
          .toList();
    } on PlatformException {
      return [];
    }
  }

  static Future<Map<String, dynamic>?> ttsInfo() async {
    try {
      return await methodChannel.invokeMapMethod<String, dynamic>('ttsInfo');
    } on PlatformException {
      return null;
    }
  }

  static Future<void> speakTest({
    required String text,
    required String lang,
    required String voice,
    required int ratePct,
  }) async {
    try {
      await methodChannel.invokeMethod('speakTest', {
        'text': text,
        'lang': lang,
        'voice': voice,
        'ratePct': ratePct,
      });
    } on PlatformException {
      // TTS engine unavailable.
    }
  }

  static Future<({bool ok, String error})> testWebhook({
    required String webhook,
    required String username,
  }) async {
    try {
      final r = await methodChannel.invokeMapMethod<String, dynamic>(
        'testWebhook',
        {'webhook': webhook, 'username': username},
      );
      return (ok: r?['ok'] == true, error: (r?['error'] ?? '').toString());
    } on PlatformException catch (e) {
      return (ok: false, error: e.message ?? 'Lỗi nền tảng');
    }
  }

  static Stream<Map<dynamic, dynamic>> notificationEvents() {
    return eventChannel.receiveBroadcastStream().map((event) {
      if (event is Map) return event;
      return <dynamic, dynamic>{};
    }).where((event) => event.isNotEmpty);
  }
}

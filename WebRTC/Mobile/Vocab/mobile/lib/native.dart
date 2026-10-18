import 'package:flutter/services.dart';

/// Bridge to MainActivity.kt: TTS and home-screen widget config.
class Native {
  static const _ch = MethodChannel('vocab/native');

  /// Speaks [text] with the Android TTS voice for [locale] (e.g. "ja-JP").
  /// Returns 'ok', 'missing' (voice not installed) or 'error'.
  static Future<String> speak(String text, String locale) async {
    if (locale.isEmpty || text.trim().isEmpty) return 'ok';
    try {
      return await _ch.invokeMethod<String>('speak', {'text': text, 'locale': locale}) ?? 'error';
    } catch (_) {
      return 'error';
    }
  }

  /// System file picker (SAF): returns the chosen file's text, or null.
  static Future<String?> openJson() => _ch.invokeMethod<String>('openJson');

  /// System "save as" dialog (SAF); true when written.
  static Future<bool> saveJson(String name, String content) async =>
      await _ch.invokeMethod<bool>('saveJson', {'name': name, 'content': content}) ?? false;

  /// TTS speed used by the app and the widgets (1.0 = normal).
  static Future<double> rate() async => await _ch.invokeMethod<double>('getRate') ?? 0.8;
  static Future<void> setRate(double rate) => _ch.invokeMethod('setRate', {'rate': rate});

  /// Opens the system screen to download TTS voices.
  static Future<void> installTts() => _ch.invokeMethod('installTts');

  static Future<List<WidgetConfig>> widgets() async {
    final list = await _ch.invokeListMethod<Map>('getWidgets') ?? [];
    return list.map((m) => WidgetConfig.fromMap(m)).toList();
  }

  static Future<void> setWidget(WidgetConfig c) => _ch.invokeMethod('setWidget', c.toMap());

  /// Redraws every widget (after collections changed).
  static Future<void> refreshWidgets() => _ch.invokeMethod('refreshWidgets');

  /// Asks the launcher to add a widget. Returns false if unsupported.
  static Future<bool> pinWidget() async => await _ch.invokeMethod<bool>('pinWidget') ?? false;
}

class WidgetConfig {
  WidgetConfig({required this.id, required this.collectionId, required this.lines, required this.autoMinutes});
  final int id;
  String collectionId;
  int lines; // 1..3
  int autoMinutes; // 0 = only via the "Đổi từ" button

  static WidgetConfig fromMap(Map m) => WidgetConfig(
    id: m['id'] as int,
    collectionId: m['collectionId'] as String? ?? '',
    lines: m['lines'] as int? ?? 2,
    autoMinutes: m['autoMinutes'] as int? ?? 0,
  );

  Map<String, dynamic> toMap() => {'id': id, 'collectionId': collectionId, 'lines': lines, 'autoMinutes': autoMinutes};
}

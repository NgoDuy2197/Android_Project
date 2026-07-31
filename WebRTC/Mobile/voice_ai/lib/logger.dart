import 'package:flutter/foundation.dart';

/// Tiny in-memory ring-buffer logger. Records recent events (speech status,
/// errors, AI requests) so the user can open a Log screen from Settings to
/// diagnose issues like the recognizer being busy.
class AppLog {
  AppLog._();
  static final AppLog instance = AppLog._();

  static const _max = 500;
  final List<String> _lines = [];

  /// Bumped on every change so the Log screen can rebuild.
  final ValueNotifier<int> revision = ValueNotifier(0);

  List<String> get lines => List.unmodifiable(_lines);

  void log(String message) {
    final now = DateTime.now();
    String two(int n) => n.toString().padLeft(2, '0');
    String three(int n) => n.toString().padLeft(3, '0');
    final ts =
        '${two(now.hour)}:${two(now.minute)}:${two(now.second)}.${three(now.millisecond)}';
    _lines.add('[$ts] $message');
    if (_lines.length > _max) _lines.removeRange(0, _lines.length - _max);
    debugPrint('VoiceAI $message');
    revision.value++;
  }

  void clear() {
    _lines.clear();
    revision.value++;
  }

  String dump() => _lines.join('\n');
}

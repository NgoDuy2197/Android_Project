import 'package:flutter/services.dart';

import 'models.dart';

/// Bridge to the native renderer, SAF pickers and widget manager.
class Native {
  static const _ch = MethodChannel('frame/native');

  /// PNG of [frame] drawn exactly like the widget at [w]x[h] px.
  /// [offset] shows another batch of photos.
  static Future<Uint8List?> render(Frame frame, int w, int h, {int offset = 0}) =>
      _ch.invokeMethod<Uint8List>('render', {'frame': frame.toJsonString(), 'w': w, 'h': h, 'offset': offset});

  static Future<int> photoCount(Frame frame, {bool rescan = false}) async =>
      await _ch.invokeMethod<int>('photoCount', {'frame': frame.toJsonString(), 'rescan': rescan}) ?? 0;

  /// {uri, name} of a folder chosen with the system picker (access kept).
  static Future<Map<String, dynamic>?> pickFolder() => _ch.invokeMapMethod<String, dynamic>('pickFolder');

  /// Chosen photo URIs (access kept).
  static Future<List<String>> pickPhotos() async {
    final m = await _ch.invokeMapMethod<String, dynamic>('pickPhotos');
    return (m?['uris'] as List?)?.cast<String>() ?? [];
  }

  static Future<List<Map<String, dynamic>>> widgets() async =>
      (await _ch.invokeListMethod<Map>('widgets') ?? []).map((m) => m.cast<String, dynamic>()).toList();

  static Future<void> assign(int id, String frameId) => _ch.invokeMethod('assign', {'id': id, 'frameId': frameId});
  static Future<void> next(int id) => _ch.invokeMethod('next', {'id': id});
  static Future<void> refreshAll() => _ch.invokeMethod('refreshAll');

  /// Asks the launcher to add a widget showing [frameId].
  static Future<bool> pin(String frameId) async => await _ch.invokeMethod<bool>('pin', {'frameId': frameId}) ?? false;
}

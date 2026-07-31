import 'package:flutter/services.dart';

import 'media_item.dart';

/// Kinds of events pushed from the native side over the EventChannel.
enum CaptureEventType { captured, recording, motion, error, busy, unknown }

class CaptureEvent {
  CaptureEvent(this.type, this.raw);

  final CaptureEventType type;
  final Map<String, dynamic> raw;

  String? get mediaType => raw['type'] as String?;
  String? get path => raw['path'] as String?;
  bool get value => raw['value'] as bool? ?? false;
  String? get message => raw['message'] as String?;
}

/// Thin wrapper over the `cameraman/native` MethodChannel and `cameraman/events`
/// EventChannel. All camera work happens natively (CameraX in a foreground
/// service); this class just issues commands and relays state.
class NativeBridge {
  NativeBridge._();
  static final NativeBridge instance = NativeBridge._();

  static const _method = MethodChannel('cameraman/native');
  static const _events = EventChannel('cameraman/events');

  Stream<CaptureEvent>? _stream;

  /// Broadcast stream of capture/recording/motion state changes. Shared so the
  /// widget-triggered captures also refresh whatever screen is open.
  Stream<CaptureEvent> get events {
    _stream ??= _events.receiveBroadcastStream().map((e) {
      final map = Map<String, dynamic>.from(e as Map);
      return CaptureEvent(_typeOf(map['event'] as String?), map);
    });
    return _stream!;
  }

  CaptureEventType _typeOf(String? name) {
    switch (name) {
      case 'captured':
        return CaptureEventType.captured;
      case 'recording':
        return CaptureEventType.recording;
      case 'motion':
        return CaptureEventType.motion;
      case 'error':
        return CaptureEventType.error;
      case 'busy':
        return CaptureEventType.busy;
      default:
        return CaptureEventType.unknown;
    }
  }

  // --- Capture commands ---------------------------------------------------

  Future<void> capturePhoto(String lens) =>
      _method.invokeMethod('capturePhoto', {'lens': lens});

  Future<void> toggleVideo(String lens) =>
      _method.invokeMethod('toggleVideo', {'lens': lens});

  Future<void> startVideo(String lens) =>
      _method.invokeMethod('startVideo', {'lens': lens});

  Future<void> stopVideo() => _method.invokeMethod('stopVideo');

  Future<void> startMotion() => _method.invokeMethod('startMotion');

  Future<void> stopMotion() => _method.invokeMethod('stopMotion');

  Future<bool> isRecording() async =>
      (await _method.invokeMethod<bool>('isRecording')) ?? false;

  Future<bool> isMotionRunning() async =>
      (await _method.invokeMethod<bool>('isMotionRunning')) ?? false;

  // --- Media / storage ----------------------------------------------------

  Future<List<MediaItem>> listMedia() async {
    final raw = await _method.invokeMethod<List<dynamic>>('listMedia') ?? [];
    return raw
        .map((e) => MediaItem.fromMap(Map<String, dynamic>.from(e as Map)))
        .toList();
  }

  Future<bool> deleteMedia(MediaItem item) async =>
      (await _method.invokeMethod<bool>('deleteMedia', {
        'uri': item.uri,
        'path': item.path,
      })) ??
      false;

  Future<void> openMedia(MediaItem item) =>
      _method.invokeMethod('openMedia', {'uri': item.uri, 'path': item.path});

  Future<String> saveLocation() async =>
      (await _method.invokeMethod<String>('saveLocation')) ?? '';

  /// Whether the user has granted "Notification access" so the app can read
  /// and forward system notifications.
  Future<bool> notiAccessGranted() async =>
      (await _method.invokeMethod<bool>('notiAccessGranted')) ?? false;

  /// Opens the system "Notification access" settings screen.
  Future<void> openNotiAccess() => _method.invokeMethod('openNotiAccess');

  /// Deletes every saved capture (photos + videos). Returns how many were
  /// removed. Used by the "forgot PIN" data wipe.
  Future<int> wipeMedia() async =>
      (await _method.invokeMethod<int>('wipeMedia')) ?? 0;

  /// Opens the save folder in a file manager (best effort). Returns a status
  /// string to show the user.
  Future<String> openFolder() async =>
      (await _method.invokeMethod<String>('openFolder')) ?? '';

  /// The phone's model name (e.g. "Samsung SM-G991B"), used as the default
  /// client name in the remote-control tab.
  Future<String> deviceName() async {
    try {
      final name = await _method.invokeMethod<String>('deviceName');
      return (name == null || name.trim().isEmpty) ? 'Thiết bị Android' : name.trim();
    } catch (_) {
      return 'Thiết bị Android';
    }
  }

  /// Opens the system folder picker; returns the chosen SAF tree uri, or null
  /// if the user cancelled.
  Future<String?> pickFolder() => _method.invokeMethod<String>('pickFolder');

  Future<bool> sendTestWebhook(String url) async =>
      (await _method.invokeMethod<bool>('sendTestWebhook', {'url': url})) ??
      false;
}

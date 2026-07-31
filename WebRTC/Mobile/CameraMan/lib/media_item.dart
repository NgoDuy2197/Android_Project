/// A single saved capture, as reported by the native `listMedia` call.
///
/// [path] is a real filesystem path when the media lives in the app's default
/// folder (the Flutter UI can render it directly). It is null for captures in a
/// user-picked Storage-Access-Framework folder, which are addressed only by
/// [uri] and opened through the system viewer.
class MediaItem {
  MediaItem({
    required this.name,
    required this.type,
    required this.time,
    required this.size,
    required this.uri,
    this.path,
  });

  final String name;
  final String type; // "photo" | "video"
  final int time; // epoch millis
  final int size; // bytes
  final String uri;
  final String? path;

  bool get isVideo => type == 'video';
  /// Captures triggered by motion detection are tagged with a "CM_MO_" prefix.
  bool get isMotion => name.toUpperCase().startsWith('CM_MO_');
  bool get hasLocalFile => path != null && path!.isNotEmpty;
  DateTime get date => DateTime.fromMillisecondsSinceEpoch(time);

  factory MediaItem.fromMap(Map<String, dynamic> map) {
    return MediaItem(
      name: map['name'] as String? ?? '',
      type: map['type'] as String? ?? 'photo',
      time: (map['time'] as num?)?.toInt() ?? 0,
      size: (map['size'] as num?)?.toInt() ?? 0,
      uri: map['uri'] as String? ?? '',
      path: map['path'] as String?,
    );
  }
}

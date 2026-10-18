import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:path_provider/path_provider.dart';

/// Frame shapes (ids match FrameRenderer.kt) with a label and a glyph.
const shapes = <(String, String, String)>[
  ('rounded', 'Bo góc', '▢'),
  ('squircle', 'iOS', '◼'),
  ('rect', 'Vuông', '■'),
  ('circle', 'Tròn', '●'),
  ('oval', 'Bầu dục', '⬮'),
  ('heart', 'Trái tim', '♥'),
  ('star', 'Ngôi sao', '★'),
  ('hexagon', 'Lục giác', '⬢'),
  ('diamond', 'Thoi', '◆'),
  ('arch', 'Vòm', '⌂'),
  ('flower', 'Bông hoa', '✿'),
  ('stamp', 'Tem thư', '✉'),
  ('polaroid', 'Polaroid', '▣'),
];

const filters = <(String, String)>[
  ('none', 'Gốc'),
  ('bw', 'Trắng đen'),
  ('sepia', 'Sepia'),
  ('warm', 'Ấm'),
  ('cool', 'Lạnh'),
  ('vintage', 'Cổ điển'),
  ('vivid', 'Rực rỡ'),
];

/// One frame design. Same JSON keys as FrameConfig in FrameRenderer.kt.
/// Sizes are "units" (1/180 of the widget's short side) so they scale with it.
class Frame {
  Frame(this.j);
  final Map<String, dynamic> j;

  String get id => j['id'] as String;
  String get name => j['name'] as String? ?? '';
  set name(String v) => j['name'] = v;

  Map<String, dynamic> get source => (j['source'] as Map?)?.cast<String, dynamic>() ?? {'type': 'samples'};
  set source(Map<String, dynamic> v) => j['source'] = v;

  T get<T>(String k, T def) => (j[k] as T?) ?? def;
  double dbl(String k, double def) => (j[k] as num?)?.toDouble() ?? def;
  void set(String k, Object? v) => j[k] = v;

  Frame copy({String? id, String? name}) {
    final c = Frame(jsonDecode(jsonEncode(j)) as Map<String, dynamic>);
    if (id != null) c.j['id'] = id;
    if (name != null) c.j['name'] = name;
    return c;
  }

  String toJsonString() => jsonEncode(j);

  String describeSource() => switch (source['type']) {
    'folder' => '📁 ${source['name'] ?? 'Thư mục'}',
    'photos' => '🖼 ${(source['uris'] as List?)?.length ?? 0} ảnh chọn',
    _ => '✨ Ảnh mẫu',
  };

  static Frame create({
    required String name,
    String shape = 'rounded',
    int count = 1,
    double border = 4,
    int borderColor = 0xFFFFFFFF,
    int matColor = 0xFFFFFFFF,
    double padding = 4,
    String filter = 'none',
    bool gloss = false,
  }) => Frame({
    'id': 'f${DateTime.now().microsecondsSinceEpoch}${name.hashCode & 0xff}',
    'name': name,
    'source': {'type': 'samples'},
    'count': count,
    'shape': shape,
    'corner': 18.0,
    'border': border,
    'borderColor': borderColor,
    'matColor': matColor,
    'padding': padding,
    'gap': 3.0,
    'photoRadius': 6.0,
    'opacity': 1.0,
    'shadow': true,
    'shadowSize': 8.0,
    'gloss': gloss,
    'filter': filter,
    'showDate': false,
    'order': 'sequential',
    'interval': 60,
    'tap': 'open',
    'nextButton': true,
  });
}

class FrameStore extends ChangeNotifier {
  final List<Frame> frames = [];
  late File _file;

  Future<void> load() async {
    _file = File('${(await getApplicationSupportDirectory()).path}/frames.json');
    if (await _file.exists()) {
      try {
        final j = jsonDecode(await _file.readAsString()) as Map<String, dynamic>;
        frames.addAll((j['frames'] as List).map((e) => Frame((e as Map).cast<String, dynamic>())));
        // Old 'tap = next' frames: the ▸ button now does next, tapping the photo opens it.
        for (final f in frames) {
          if (f.j['tap'] == 'next') f.j['tap'] = 'open';
        }
      } catch (_) {}
    }
    if (frames.isEmpty) {
      frames.addAll([
        Frame.create(name: 'Kỷ niệm'),
        Frame.create(name: 'Trái tim', shape: 'heart', count: 2, border: 5, borderColor: 0xFFFF6B9A, matColor: 0xFFFFE3EC),
        Frame.create(name: 'Album 4 ảnh', shape: 'squircle', count: 4, border: 3, gloss: true),
        Frame.create(name: 'Polaroid', shape: 'polaroid', border: 0, padding: 8),
        Frame.create(name: 'Tem thư', shape: 'stamp', border: 0, matColor: 0xFFFFF6E5, padding: 7, filter: 'vintage'),
      ]);
      await save();
    }
  }

  Future<void> save() async {
    final tmp = File('${_file.path}.tmp');
    await tmp.writeAsString(jsonEncode({'frames': frames.map((f) => f.j).toList()}));
    await tmp.rename(_file.path);
    notifyListeners();
  }

  Frame? byId(String id) {
    for (final f in frames) {
      if (f.id == id) return f;
    }
    return null;
  }
}

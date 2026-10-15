import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:path_provider/path_provider.dart';

/// Body parts an item can be worn on. Each part holds at most one item.
enum BodyPart {
  head('Đầu', 'Mũ / nón', Icons.face_retouching_natural),
  halo('Hào quang', 'Vòng thiên sứ / trang trí trên đầu', Icons.blur_circular),
  face('Mặt', 'Khẩu trang', Icons.masks_outlined),
  eyes('Mắt', 'Kính', Icons.visibility_outlined),
  wings('Cánh', 'Cánh', Icons.flutter_dash),
  top('Áo', 'Áo', Icons.checkroom),
  bottom('Quần', 'Quần', Icons.straighten),
  skirt('Chân váy', 'Chân váy', Icons.woman),
  dress('Váy dài', 'Váy liền dài (từ vai tới chân)', Icons.woman_2),
  feet('Giày', 'Giày', Icons.do_not_step);

  const BodyPart(this.label, this.itemLabel, this.icon);
  final String label;
  final String itemLabel;
  final IconData icon;
}

class Product {
  Product({
    required this.id,
    required this.name,
    required this.part,
    required this.path,
  });

  final String id;
  String name;
  final BodyPart part;
  final String path;

  Map<String, dynamic> toJson() =>
      {'id': id, 'name': name, 'part': part.name, 'path': path};

  static Product fromJson(Map<String, dynamic> j) => Product(
    id: j['id'] as String,
    name: j['name'] as String,
    part: BodyPart.values.byName(j['part'] as String),
    path: j['path'] as String,
  );
}

/// Manual size/position correction applied on top of the automatic fit.
class PartAdjust {
  double scale = 1;
  double width = 1;
  double height = 1;
  double dx = 0; // fraction of item width
  double dy = 0; // fraction of item height, + = down
  bool flip = false;


  bool get isDefault =>
      scale == 1 && width == 1 && height == 1 && dx == 0 && dy == 0 && !flip;

  void reset() {
    scale = width = height = 1;
    dx = dy = 0;
    flip = false;
  }

  Map<String, dynamic> toJson() => {
    'scale': scale,
    'width': width,
    'height': height,
    'dx': dx,
    'dy': dy,
    'flip': flip,
  };

  static PartAdjust fromJson(Map<String, dynamic> j) => PartAdjust()
    ..scale = (j['scale'] as num?)?.toDouble() ?? 1
    ..width = (j['width'] as num?)?.toDouble() ?? 1
    ..height = (j['height'] as num?)?.toDouble() ?? 1
    ..dx = (j['dx'] as num?)?.toDouble() ?? 0
    ..dy = (j['dy'] as num?)?.toDouble() ?? 0
    ..flip = j['flip'] as bool? ?? false;
}

/// Local "cart": imported products, what is being worn and per-part tweaks.
/// Persisted as JSON + PNG files in the app documents directory.
class CartStore extends ChangeNotifier {
  final List<Product> products = [];
  final Map<BodyPart, String> worn = {};
  final Map<BodyPart, PartAdjust> adjust = {
    for (final p in BodyPart.values) p: PartAdjust(),
  };
  bool mirrorFix = false;

  late Directory _dir;

  Directory get productDir => Directory('${_dir.path}/products');
  File get _file => File('${_dir.path}/cart.json');

  /// Bundled example items, each copied into the cart once (deleting one
  /// later is permanent). New samples added in app updates are still seeded.
  static const _samples = {
    BodyPart.head: ('cap', 'Mũ lưỡi trai (mẫu)'),
    BodyPart.face: ('mask', 'Khẩu trang y tế (mẫu)'),
    BodyPart.eyes: ('glasses', 'Kính râm (mẫu)'),
    BodyPart.top: ('tshirt', 'Áo thun đỏ (mẫu)'),
    BodyPart.bottom: ('jeans', 'Quần jeans (mẫu)'),
    BodyPart.skirt: ('skirt', 'Chân váy xoè (mẫu)'),
    BodyPart.dress: ('dress', 'Váy dài dạ hội (mẫu)'),
    BodyPart.wings: ('wings', 'Cánh thiên thần (mẫu)'),
    BodyPart.halo: ('halo', 'Vòng thiên sứ (mẫu)'),
    BodyPart.feet: ('sneaker', 'Giày sneaker (mẫu)'),
  };
  static const _firstSamples = ['cap', 'mask', 'glasses', 'tshirt', 'jeans', 'sneaker'];
  final Set<String> _seeded = {};

  Future<void> load() async {
    _dir = await getApplicationDocumentsDirectory();
    await productDir.create(recursive: true);
    if (await _file.exists()) _read();
    await _seedSamples();
  }

  Future<void> _seedSamples() async {
    var added = false;
    for (final e in _samples.entries) {
      final (asset, name) = e.value;
      if (_seeded.contains(asset)) continue;
      try {
        final data = await rootBundle.load('assets/samples/$asset.png');
        final file = File('${productDir.path}/sample_$asset.png');
        await file.writeAsBytes(data.buffer.asUint8List(data.offsetInBytes, data.lengthInBytes));
        products.add(Product(id: 'sample_$asset', name: name, part: e.key, path: file.path));
        _seeded.add(asset);
        added = true;
      } catch (_) {}
    }
    for (final e in _sampleBackdrops.entries) {
      if (_seeded.contains(e.key)) continue;
      try {
        final data = await rootBundle.load('assets/samples/${e.key}.jpg');
        final file = File('${productDir.path}/sample_${e.key}.jpg');
        await file.writeAsBytes(data.buffer.asUint8List(data.offsetInBytes, data.lengthInBytes));
        backdrops.add(Backdrop(id: 'sample_${e.key}', name: e.value, path: file.path));
        _seeded.add(e.key);
        added = true;
      } catch (_) {}
    }
    if (added) await _save();
  }

  void _read() {
    try {
      final j = jsonDecode(_file.readAsStringSync()) as Map<String, dynamic>;
      // v1 stored `seeded: true` for the first six samples.
      if (j['seeded'] == true) _seeded.addAll(_firstSamples);
      _seeded.addAll((j['seededSamples'] as List? ?? []).cast<String>());
      products.addAll(
        (j['products'] as List)
            .map((e) => Product.fromJson(e as Map<String, dynamic>))
            .where((p) => File(p.path).existsSync()),
      );
      (j['worn'] as Map<String, dynamic>? ?? {}).forEach((k, v) {
        final part = BodyPart.values.asNameMap()[k];
        if (part != null && products.any((p) => p.id == v)) worn[part] = v as String;
      });
      (j['adjust'] as Map<String, dynamic>? ?? {}).forEach((k, v) {
        final part = BodyPart.values.asNameMap()[k];
        if (part != null) adjust[part] = PartAdjust.fromJson(v as Map<String, dynamic>);
      });
      mirrorFix = j['mirrorFix'] as bool? ?? false;
      backdrops.addAll(
        (j['backdrops'] as List? ?? [])
            .map((e) => Backdrop.fromJson(e as Map<String, dynamic>))
            .where((b) => File(b.path).existsSync()),
      );
      final bid = j['backdropId'] as String?;
      if (backdrops.any((b) => b.id == bid)) backdropId = bid;
    } catch (_) {
      // Corrupt file: start with an empty cart rather than crashing.
    }
  }

  Future<void> _save() async {
    await _file.writeAsString(jsonEncode({
      'products': products.map((p) => p.toJson()).toList(),
      'worn': worn.map((k, v) => MapEntry(k.name, v)),
      'adjust': adjust.map((k, v) => MapEntry(k.name, v.toJson())),
      'mirrorFix': mirrorFix,
      'seededSamples': _seeded.toList(),
      'backdrops': backdrops.map((b) => b.toJson()).toList(),
      'backdropId': backdropId,
    }));
  }

  Timer? _saveTimer;

  /// Bumped on every change so painters know to redraw.
  int revision = 0;

  void changed() {
    revision++;
    notifyListeners();
    _saveTimer?.cancel();
    _saveTimer = Timer(const Duration(milliseconds: 400), _save);
  }

  List<Product> itemsFor(BodyPart part) =>
      products.where((p) => p.part == part).toList();

  Product? wornItem(BodyPart part) {
    final id = worn[part];
    if (id == null) return null;
    for (final p in products) {
      if (p.id == id) return p;
    }
    return null;
  }

  void add(Product p, {bool wear = true}) {
    products.add(p);
    if (wear) _put(p.part, p.id);
    changed();
  }

  void wear(BodyPart part, String? id) {
    if (id == null) {
      worn.remove(part);
    } else {
      _put(part, id);
    }
    changed();
  }

  /// Parts that cover the same area: wearing one takes the others off.
  /// A long dress replaces top, pants and skirt.
  static const _clashes = {
    BodyPart.top: [BodyPart.dress],
    BodyPart.bottom: [BodyPart.skirt, BodyPart.dress],
    BodyPart.skirt: [BodyPart.bottom, BodyPart.dress],
    BodyPart.dress: [BodyPart.top, BodyPart.bottom, BodyPart.skirt],
  };

  void _put(BodyPart part, String id) {
    worn[part] = id;
    for (final other in _clashes[part] ?? const <BodyPart>[]) {
      worn.remove(other);
    }
  }

  // ------------------------------------------------------------ backdrops

  /// Background pictures: only the person is kept on top of the chosen one.
  final List<Backdrop> backdrops = [];
  String? backdropId;

  static const _sampleBackdrops = {
    'bg_heaven': 'Thiên đường',
    'bg_studio': 'Studio hồng',
    'bg_sunset': 'Hoàng hôn',
    'bg_city': 'Phố đêm',
  };

  Backdrop? get backdrop {
    for (final b in backdrops) {
      if (b.id == backdropId) return b;
    }
    return null;
  }

  /// Person segmentation is needed for a backdrop and to tuck wings
  /// behind the body.
  bool get needsSegmentation => backdrop != null || wornItem(BodyPart.wings) != null;

  void selectBackdrop(String? id) {
    backdropId = id;
    changed();
  }

  void addBackdrop(Backdrop b) {
    backdrops.add(b);
    backdropId = b.id;
    changed();
  }

  Future<void> removeBackdrop(Backdrop b) async {
    backdrops.removeWhere((e) => e.id == b.id);
    if (backdropId == b.id) backdropId = null;
    changed();
    try {
      await File(b.path).delete();
    } catch (_) {}
  }

  Future<void> remove(Product p) async {
    products.removeWhere((e) => e.id == p.id);
    if (worn[p.part] == p.id) worn.remove(p.part);
    changed();
    try {
      await File(p.path).delete();
    } catch (_) {}
  }
}

/// A background picture for "only the person" mode.
class Backdrop {
  Backdrop({required this.id, required this.name, required this.path});
  final String id;
  final String name;
  final String path;

  Map<String, dynamic> toJson() => {'id': id, 'name': name, 'path': path};

  static Backdrop fromJson(Map<String, dynamic> j) =>
      Backdrop(id: j['id'] as String, name: j['name'] as String, path: j['path'] as String);
}
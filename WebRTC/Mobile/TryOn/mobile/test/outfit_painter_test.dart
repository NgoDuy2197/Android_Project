import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_mlkit_pose_detection/google_mlkit_pose_detection.dart';
import 'package:try_on_cam/models.dart';
import 'package:try_on_cam/outfit_painter.dart';

Future<ui.Image> _solid(int w, int h, Color c) async {
  final rec = ui.PictureRecorder();
  Canvas(rec).drawRect(Rect.fromLTWH(0, 0, w.toDouble(), h.toDouble()), Paint()..color = c);
  return rec.endRecording().toImage(w, h);
}

Future<List<int>> _rgbaAt(ui.Image img, int x, int y) async {
  final data = (await img.toByteData(format: ui.ImageByteFormat.rawRgba))!;
  final i = (y * img.width + x) * 4;
  return [for (var k = 0; k < 4; k++) data.getUint8(i + k)];
}

/// 100x100 frame, person = centre column x 40..60 (mask confidence 1).
Future<PersonMask> _mask() async {
  final conf = List<double>.generate(100 * 100, (i) {
    final x = i % 100;
    return x >= 40 && x < 60 ? 1.0 : 0.0;
  });
  return PersonMask(await maskImage(conf, 100, 100));
}

BodyPoints _body() => BodyPoints({
  PoseLandmarkType.leftShoulder: const Offset(58, 40),
  PoseLandmarkType.rightShoulder: const Offset(42, 40),
  PoseLandmarkType.leftHip: const Offset(56, 65),
  PoseLandmarkType.rightHip: const Offset(44, 65),
}, const Size(100, 100));

Future<ui.Image> _render(void Function(Canvas) paint) {
  final rec = ui.PictureRecorder();
  paint(Canvas(rec));
  return rec.endRecording().toImage(100, 100);
}

void main() {
  testWidgets('backdrop covers everything except the person', (tester) async {
    await tester.runAsync(() async {
      final mask = await _mask();
      final bg = await _solid(10, 10, const Color(0xFF00FF00));
      final out = await _render((c) => paintOutfit(c, _body(), {}, {}, mask: mask, backdrop: bg));
      expect(await _rgbaAt(out, 10, 50), [0, 255, 0, 255]); // background
      expect((await _rgbaAt(out, 50, 50))[3], 0); // person stays camera
    });
  });

  testWidgets('wings are hidden where the person is', (tester) async {
    await tester.runAsync(() async {
      final mask = await _mask();
      final wings = await _solid(40, 20, const Color(0xFFFFFFFF));
      final items = {BodyPart.wings: wings};
      final adjust = {BodyPart.wings: PartAdjust()};
      final out = await _render((c) => paintOutfit(c, _body(), items, adjust, mask: mask));
      // Wings centre on the upper back: behind the body, visible beside it.
      expect((await _rgbaAt(out, 50, 45))[3], 0);
      expect((await _rgbaAt(out, 30, 45))[3], 255);
    });
  });

  test('long dress replaces top, pants and skirt', () {
    final store = CartStore();
    store.worn[BodyPart.top] = 'a';
    store.worn[BodyPart.skirt] = 'b';
    store.products.add(Product(id: 'd', name: 'd', part: BodyPart.dress, path: 'x'));
    store.wear(BodyPart.dress, 'd');
    expect(store.worn.keys, [BodyPart.dress]);
    store.wear(BodyPart.top, 'a');
    expect(store.worn.containsKey(BodyPart.dress), false);
  });
}

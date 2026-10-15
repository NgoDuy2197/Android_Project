import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:image/image.dart' as img;
import 'package:try_on_cam/image_tools.dart';

/// 100x100 green screen, red 40x40 product in the middle with a green
/// 10x10 detail inside it.
EditableImage _greenScreen() {
  final px = Uint8List(100 * 100 * 4);
  for (var y = 0; y < 100; y++) {
    for (var x = 0; x < 100; x++) {
      final i = (y * 100 + x) * 4;
      final inProduct = x >= 30 && x < 70 && y >= 30 && y < 70;
      final inDetail = x >= 45 && x < 55 && y >= 45 && y < 55;
      final green = !inProduct || inDetail;
      px[i] = green ? 20 : 220;
      px[i + 1] = green ? 200 : 30;
      px[i + 2] = green ? 40 : 30;
      px[i + 3] = 255;
    }
  }
  return EditableImage(px, 100, 100, Uint8List(0));
}

img.Image _run(EditableImage src, {required bool borderOnly}) {
  final key = src.sampleColor(5, 5);
  final png = prepareProductImage(
    PrepareArgs(src, autoRemove: false, keyColors: [key], keyBorderOnly: borderOnly),
  );
  return img.decodePng(png)!;
}

void main() {
  test('picked colour is sampled from the image', () {
    expect(_greenScreen().sampleColor(5, 5), 0x14C828);
  });

  test('picked colour removes the green screen and crops to the product', () {
    final out = _run(_greenScreen(), borderOnly: false);
    expect(out.width, 40);
    expect(out.height, 40);
    expect(out.getPixel(2, 2).a, 255); // red product kept
    expect(out.getPixel(20, 20).a, 0); // inner green detail also removed
  });

  test('border-only mode keeps same-coloured details inside the product', () {
    final out = _run(_greenScreen(), borderOnly: true);
    expect(out.width, 40);
    expect(out.getPixel(2, 2).a, 255);
    expect(out.getPixel(20, 20).a, 255);
  });
}

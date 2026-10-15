import 'dart:collection';
import 'dart:math' as math;
import 'dart:typed_data';

import 'package:image/image.dart' as img;

/// A picked photo decoded once (upright, at most 1000 px) so it can be shown
/// for colour picking and re-processed quickly while the user tweaks options.
class EditableImage {
  EditableImage(this.rgba, this.width, this.height, this.png);
  final Uint8List rgba;
  final int width;
  final int height;

  /// PNG of the untouched image, shown in colour-picking mode.
  final Uint8List png;

  /// Average colour (0xRRGGBB) of a small square around pixel (x, y).
  int sampleColor(int x, int y, {int radius = 2}) {
    var r = 0, g = 0, b = 0, n = 0;
    for (var yy = math.max(0, y - radius); yy <= math.min(height - 1, y + radius); yy++) {
      for (var xx = math.max(0, x - radius); xx <= math.min(width - 1, x + radius); xx++) {
        final i = (yy * width + xx) * 4;
        r += rgba[i];
        g += rgba[i + 1];
        b += rgba[i + 2];
        n++;
      }
    }
    return ((r ~/ n) << 16) | ((g ~/ n) << 8) | (b ~/ n);
  }
}

/// Decodes picked photo bytes (runs in an isolate via `compute`).
EditableImage? decodeForEdit(Uint8List bytes) {
  final decoded = img.decodeImage(bytes);
  if (decoded == null) return null;
  var src = img.bakeOrientation(decoded);
  const maxSide = 1000;
  if (math.max(src.width, src.height) > maxSide) {
    src = src.width >= src.height
        ? img.copyResize(src, width: maxSide, interpolation: img.Interpolation.average)
        : img.copyResize(src, height: maxSide, interpolation: img.Interpolation.average);
  }
  final rgba = src.convert(format: img.Format.uint8, numChannels: 4);
  return EditableImage(
    Uint8List.fromList(rgba.getBytes(order: img.ChannelOrder.rgba)),
    rgba.width,
    rgba.height,
    img.encodePng(rgba),
  );
}

/// Arguments for [prepareProductImage] (runs in an isolate via `compute`).
class PrepareArgs {
  PrepareArgs(
    this.image, {
    required this.autoRemove,
    this.tolerance = 42,
    this.keyColors = const [],
    this.keyBorderOnly = false,
  });
  final EditableImage image;

  /// Erase the plain background guessed from the border colour.
  final bool autoRemove;
  final int tolerance;

  /// Colours (0xRRGGBB) the user picked to erase, e.g. a green screen.
  final List<int> keyColors;

  /// Erase picked colours only where connected to the image border
  /// (keeps same-coloured details inside the product).
  final bool keyBorderOnly;
}

/// Erases the background (auto and/or picked colours), trims empty margins
/// and returns PNG bytes.
Uint8List prepareProductImage(PrepareArgs a) {
  final w = a.image.width, h = a.image.height;
  final px = Uint8List.fromList(a.image.rgba);

  if (a.autoRemove && !_hasTransparentBorder(px, w, h)) {
    _floodErase(px, w, h, a.tolerance);
  }
  if (a.keyColors.isNotEmpty) {
    _keyErase(px, w, h, a.keyColors, a.tolerance, a.keyBorderOnly);
  }

  // Trim fully transparent margins.
  var minX = w, minY = h, maxX = -1, maxY = -1;
  for (var y = 0; y < h; y++) {
    for (var x = 0; x < w; x++) {
      if (px[(y * w + x) * 4 + 3] > 16) {
        if (x < minX) minX = x;
        if (x > maxX) maxX = x;
        if (y < minY) minY = y;
        if (y > maxY) maxY = y;
      }
    }
  }
  var out = img.Image.fromBytes(
    width: w,
    height: h,
    bytes: px.buffer,
    numChannels: 4,
    order: img.ChannelOrder.rgba,
  );
  if (maxX >= minX && maxY >= minY && (maxX - minX + 1 < w || maxY - minY + 1 < h)) {
    out = img.copyCrop(out, x: minX, y: minY, width: maxX - minX + 1, height: maxY - minY + 1);
  }
  return img.encodePng(out);
}

/// Chroma-key style removal of user-picked colours with a soft edge band.
void _keyErase(Uint8List px, int w, int h, List<int> keys, int tol, bool borderOnly) {
  final tol2 = tol * tol;
  final soft = tol * 1.6;
  final soft2 = soft * soft;

  // Squared distance to the closest picked colour.
  int nearest(int i) {
    var best = 1 << 30;
    for (final k in keys) {
      final dr = px[i] - ((k >> 16) & 0xFF);
      final dg = px[i + 1] - ((k >> 8) & 0xFF);
      final db = px[i + 2] - (k & 0xFF);
      final d = dr * dr + dg * dg + db * db;
      if (d < best) best = d;
    }
    return best;
  }

  final n = w * h;
  final dist = Int32List(n);
  for (var p = 0; p < n; p++) {
    dist[p] = px[p * 4 + 3] == 0 ? 0 : nearest(p * 4);
  }

  // Which pixels may be touched: everything, or only regions reachable from
  // the border through key-coloured pixels (the soft band included).
  Uint8List? allowed;
  if (borderOnly) {
    allowed = Uint8List(n);
    final queue = Queue<int>();
    void seed(int p) {
      if (allowed![p] == 0 && dist[p] <= soft2) {
        allowed[p] = 1;
        queue.add(p);
      }
    }

    for (var x = 0; x < w; x++) {
      seed(x);
      seed((h - 1) * w + x);
    }
    for (var y = 0; y < h; y++) {
      seed(y * w);
      seed(y * w + w - 1);
    }
    while (queue.isNotEmpty) {
      final p = queue.removeFirst();
      final x = p % w, y = p ~/ w;
      // Only spread further from fully-matching pixels so the edge band
      // does not leak into the product.
      if (dist[p] > tol2) continue;
      if (x > 0) seed(p - 1);
      if (x < w - 1) seed(p + 1);
      if (y > 0) seed(p - w);
      if (y < h - 1) seed(p + w);
    }
  }

  for (var p = 0; p < n; p++) {
    if (allowed != null && allowed[p] == 0) continue;
    final d = dist[p];
    if (d <= tol2) {
      px[p * 4 + 3] = 0;
    } else if (d < soft2) {
      final t = (math.sqrt(d) - tol) / (soft - tol);
      final a = (t * 255).round();
      if (a < px[p * 4 + 3]) px[p * 4 + 3] = a;
    }
  }
}

bool _hasTransparentBorder(Uint8List px, int w, int h) {
  var transparent = 0, total = 0;
  for (var x = 0; x < w; x += 4) {
    total += 2;
    if (px[x * 4 + 3] < 200) transparent++;
    if (px[((h - 1) * w + x) * 4 + 3] < 200) transparent++;
  }
  return transparent > total * 0.3;
}

/// Erases pixels connected to the border whose colour is close to the
/// average border colour, then softens the cut edge by one pixel.
void _floodErase(Uint8List px, int w, int h, int tol) {
  // Average colour of the border ring = background guess.
  var r = 0, g = 0, b = 0, n = 0;
  void sample(int x, int y) {
    final i = (y * w + x) * 4;
    r += px[i];
    g += px[i + 1];
    b += px[i + 2];
    n++;
  }

  for (var x = 0; x < w; x++) {
    sample(x, 0);
    sample(x, h - 1);
  }
  for (var y = 0; y < h; y++) {
    sample(0, y);
    sample(w - 1, y);
  }
  final br = r ~/ n, bg = g ~/ n, bb = b ~/ n;
  final tol2 = tol * tol;

  int dist2(int i) {
    final dr = px[i] - br, dg = px[i + 1] - bg, db = px[i + 2] - bb;
    return dr * dr + dg * dg + db * db;
  }

  final removed = Uint8List(w * h);
  final queue = Queue<int>();
  void seed(int x, int y) {
    final p = y * w + x;
    if (removed[p] == 0 && dist2(p * 4) <= tol2) {
      removed[p] = 1;
      queue.add(p);
    }
  }

  for (var x = 0; x < w; x++) {
    seed(x, 0);
    seed(x, h - 1);
  }
  for (var y = 0; y < h; y++) {
    seed(0, y);
    seed(w - 1, y);
  }
  while (queue.isNotEmpty) {
    final p = queue.removeFirst();
    final x = p % w, y = p ~/ w;
    if (x > 0) seed(x - 1, y);
    if (x < w - 1) seed(x + 1, y);
    if (y > 0) seed(x, y - 1);
    if (y < h - 1) seed(x, y + 1);
  }

  for (var p = 0; p < w * h; p++) {
    if (removed[p] == 1) {
      px[p * 4 + 3] = 0;
    }
  }
  // Feather: kept pixels next to a removed one get partial alpha.
  for (var y = 1; y < h - 1; y++) {
    for (var x = 1; x < w - 1; x++) {
      final p = y * w + x;
      if (removed[p] == 1) continue;
      final edge = removed[p - 1] + removed[p + 1] + removed[p - w] + removed[p + w];
      if (edge > 0) {
        final d = math.sqrt(dist2(p * 4)) / (tol * 2.2);
        final a = (math.min(1.0, d) * 255).round();
        if (a < px[p * 4 + 3]) px[p * 4 + 3] = a;
      }
    }
  }
}

/// Bakes EXIF orientation into a photo, caps it at 2048 px and re-encodes it
/// as JPEG, so pose detection and display see the very same pixels.
/// Returns (jpeg, width, height) or null if undecodable.
(Uint8List, int, int)? normalizePhoto(Uint8List bytes) {
  final decoded = img.decodeImage(bytes);
  if (decoded == null) return null;
  var src = img.bakeOrientation(decoded);
  const maxSide = 2048;
  if (math.max(src.width, src.height) > maxSide) {
    src = src.width >= src.height
        ? img.copyResize(src, width: maxSide, interpolation: img.Interpolation.average)
        : img.copyResize(src, height: maxSide, interpolation: img.Interpolation.average);
  }
  return (img.encodeJpg(src, quality: 92), src.width, src.height);
}

/// Encodes raw RGBA pixels (width, height) as JPEG; run via `compute`.
Uint8List encodeJpgRgba((Uint8List, int, int) a) {
  final image = img.Image.fromBytes(
    width: a.$2,
    height: a.$3,
    bytes: a.$1.buffer,
    numChannels: 4,
    order: img.ChannelOrder.rgba,
  );
  return img.encodeJpg(image, quality: 92);
}

/// Arguments for [nv21ToUprightRgba].
class FrameArgs {
  FrameArgs({
    required this.bytes,
    required this.width,
    required this.height,
    required this.stride,
    required this.rotation,
    required this.mirror,
  });
  final Uint8List bytes;
  final int width;
  final int height;
  final int stride;
  final int rotation; // clockwise degrees needed to make the frame upright
  final bool mirror;
}

/// Converts an NV21 camera frame into upright (and optionally mirrored)
/// RGBA pixels so the saved photo matches what was shown on screen.
Uint8List nv21ToUprightRgba(FrameArgs f) {
  final w = f.width, h = f.height, s = f.stride;
  final rot = f.rotation % 360;
  final ow = (rot == 90 || rot == 270) ? h : w;
  final oh = (rot == 90 || rot == 270) ? w : h;
  final out = Uint8List(ow * oh * 4);
  final y0 = f.bytes;
  final uvStart = s * h;
  var o = 0;
  for (var oy = 0; oy < oh; oy++) {
    for (var oxr = 0; oxr < ow; oxr++) {
      final ox = f.mirror ? ow - 1 - oxr : oxr;
      int sx, sy;
      switch (rot) {
        case 90:
          sx = oy;
          sy = h - 1 - ox;
        case 180:
          sx = w - 1 - ox;
          sy = h - 1 - oy;
        case 270:
          sx = w - 1 - oy;
          sy = ox;
        default:
          sx = ox;
          sy = oy;
      }
      final yv = y0[sy * s + sx];
      final uvi = uvStart + (sy >> 1) * s + (sx & ~1);
      final v = y0[uvi] - 128;
      final u = y0[uvi + 1] - 128;
      final r = yv + ((1436 * v) >> 10);
      final g = yv - ((352 * u + 731 * v) >> 10);
      final b = yv + ((1815 * u) >> 10);
      out[o] = r < 0 ? 0 : (r > 255 ? 255 : r);
      out[o + 1] = g < 0 ? 0 : (g > 255 ? 255 : g);
      out[o + 2] = b < 0 ? 0 : (b > 255 ? 255 : b);
      out[o + 3] = 255;
      o += 4;
    }
  }
  return out;
}

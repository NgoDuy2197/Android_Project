import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:camera/camera.dart';
import 'package:flutter/services.dart';
import 'package:path_provider/path_provider.dart';

import 'models.dart';
import 'outfit_painter.dart';

/// Records the camera stream with the outfit burnt in, via the native
/// H.264 encoder in `VideoEncoder.kt`.
class OutfitVideoRecorder {
  OutfitVideoRecorder._(this.path, this.width, this.height, this.rotation);

  static const _ch = MethodChannel('tryon/video');

  final String path;
  final int width; // sensor-oriented frame size
  final int height;
  final int rotation;
  final Stopwatch elapsed = Stopwatch()..start();
  int _inFlight = 0;
  bool _overlayBusy = false;
  bool _closed = false;

  static Future<OutfitVideoRecorder> start({
    required int width,
    required int height,
    required int rotation,
    required bool mirror,
  }) async {
    final dir = await getTemporaryDirectory();
    final path = '${dir.path}/tryon_${DateTime.now().millisecondsSinceEpoch}.mp4';
    await _ch.invokeMethod('start', {
      'path': path,
      'width': width,
      'height': height,
      'rotation': rotation,
      'mirror': mirror,
    });
    return OutfitVideoRecorder._(path, width, height, rotation);
  }

  /// Sends one NV21 camera frame; dropped if the encoder is behind.
  void pushFrame(CameraImage image) {
    if (_closed || _inFlight >= 2 || image.planes.length != 1) return;
    if (image.width != width || image.height != height) return;
    final plane = image.planes.first;
    final stride = plane.bytes.length >= plane.bytesPerRow * height * 3 ~/ 2 ? plane.bytesPerRow : width;
    _inFlight++;
    _ch.invokeMethod('frame', {
      'nv21': plane.bytes,
      'stride': stride,
      'ts': DateTime.now().microsecondsSinceEpoch,
    }).whenComplete(() => _inFlight--);
  }

  /// Renders the outfit at half frame size in sensor orientation and hands it
  /// to the encoder (used for every following frame until replaced).
  Future<void> updateOverlay(
    BodyPoints body,
    Map<BodyPart, ui.Image> items,
    Map<BodyPart, PartAdjust> adjust, {
    PersonMask? mask,
    ui.Image? backdrop,
  }) async {
    if (_closed || _overlayBusy) return;
    final hasBackdrop = backdrop != null && mask != null;
    if (!hasBackdrop && (items.isEmpty || body.isEmpty)) {
      await _ch.invokeMethod('overlay', {'rgba': null});
      return;
    }
    _overlayBusy = true;
    try {
      final ow = width ~/ 2, oh = height ~/ 2;
      final rec = ui.PictureRecorder();
      final canvas = ui.Canvas(rec);
      canvas.scale(0.5);
      canvas.transform(_displayToSensor(body.imageSize));
      paintOutfit(canvas, body, items, adjust, mask: mask, backdrop: backdrop);
      final image = await rec.endRecording().toImage(ow, oh);
      final data = await image.toByteData(format: ui.ImageByteFormat.rawRgba);
      image.dispose();
      if (data == null || _closed) return;
      await _ch.invokeMethod('overlay', {
        'rgba': data.buffer.asUint8List(),
        'width': ow,
        'height': oh,
      });
    } finally {
      _overlayBusy = false;
    }
  }

  /// Maps upright display coordinates onto the (mirror-flipped) sensor
  /// frame the encoder writes; mirroring is done natively on the pixels.
  Float64List _displayToSensor(ui.Size upright) {
    final w = width.toDouble(), h = height.toDouble();
    // sx = a*x + b*y + c ; sy = d*x + e*y + f
    double a, b, c, d, e, f;
    switch (rotation) {
      case 90:
        (a, b, c, d, e, f) = (0, 1, 0, -1, 0, h);
      case 180:
        (a, b, c, d, e, f) = (-1, 0, w, 0, -1, h);
      case 270:
        (a, b, c, d, e, f) = (0, -1, w, 1, 0, 0);
      default:
        (a, b, c, d, e, f) = (1, 0, 0, 0, 1, 0);
    }
    return Float64List.fromList([a, d, 0, 0, b, e, 0, 0, 0, 0, 1, 0, c, f, 0, 1]);
  }

  /// Finalises the MP4. Returns its path, or null if nothing was recorded.
  Future<String?> stop() async {
    _closed = true;
    elapsed.stop();
    final ok = await _ch.invokeMethod<bool>('stop') ?? false;
    if (ok) return path;
    try {
      await File(path).delete();
    } catch (_) {}
    return null;
  }
}

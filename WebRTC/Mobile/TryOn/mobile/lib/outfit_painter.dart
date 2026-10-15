import 'dart:async';
import 'dart:math' as math;
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:google_mlkit_pose_detection/google_mlkit_pose_detection.dart';

import 'models.dart';

typedef L = PoseLandmarkType;

/// Smoothed body landmarks in upright (and display-mirrored) image pixels.
class BodyPoints {
  BodyPoints(this.points, this.imageSize);
  final Map<PoseLandmarkType, Offset> points;
  final Size imageSize;

  /// Unsmoothed points of a single detection (still photos).
  factory BodyPoints.fromPose(Pose pose, Size imageSize, {double minLikelihood = 0.3}) =>
      BodyPoints({
        for (final e in pose.landmarks.entries)
          if (e.value.likelihood >= minLikelihood) e.key: Offset(e.value.x, e.value.y),
      }, imageSize);

  bool get isEmpty => points.isEmpty;
  Offset? operator [](PoseLandmarkType t) => points[t];

  Offset? mid(PoseLandmarkType a, PoseLandmarkType b) {
    final pa = points[a], pb = points[b];
    if (pa == null || pb == null) return null;
    return (pa + pb) / 2;
  }

  double? dist(PoseLandmarkType a, PoseLandmarkType b) {
    final pa = points[a], pb = points[b];
    if (pa == null || pb == null) return null;
    return (pa - pb).distance;
  }

  /// Angle of the line aâ†’b, normalised so it points to the right
  /// (keeps items upright whichever side is the person's left).
  double? lineAngle(PoseLandmarkType a, PoseLandmarkType b) {
    final pa = points[a], pb = points[b];
    if (pa == null || pb == null) return null;
    var v = pb - pa;
    if (v.dx < 0) v = -v;
    return math.atan2(v.dy, v.dx);
  }
}

/// Filters ML Kit output: drops low-confidence points, keeps briefly lost
/// points for a moment and applies adaptive exponential smoothing so items
/// do not jitter.
class PoseTracker {
  final _pts = <PoseLandmarkType, Offset>{};
  final _seen = <PoseLandmarkType, int>{};
  Size _size = Size.zero;

  static const _minLikelihood = 0.5;
  static const _holdMs = 350;

  void reset() {
    _pts.clear();
    _seen.clear();
  }

  void update(Pose? pose, Size imageSize, bool mirror) {
    final now = DateTime.now().millisecondsSinceEpoch;
    if (imageSize != _size) {
      reset();
      _size = imageSize;
    }
    if (pose != null) {
      final fast = imageSize.width * 0.06;
      pose.landmarks.forEach((type, lm) {
        if (lm.likelihood < _minLikelihood) return;
        final raw = Offset(mirror ? imageSize.width - lm.x : lm.x, lm.y);
        final old = _pts[type];
        final fresh = old != null && now - (_seen[type] ?? 0) < _holdMs;
        if (!fresh) {
          _pts[type] = raw;
        } else {
          final a = ((raw - old).distance / fast).clamp(0.35, 0.9);
          _pts[type] = Offset.lerp(old, raw, a)!;
        }
        _seen[type] = now;
      });
    }
    _seen.removeWhere((t, ts) {
      final stale = now - ts > _holdMs;
      if (stale) _pts.remove(t);
      return stale;
    });
  }

  BodyPoints snapshot() => BodyPoints(Map.of(_pts), _size);
}

/// Where and how big to draw one item copy (shoes produce two).
class Placement {
  Placement({
    required this.anchor,
    required this.angle,
    required this.width,
    this.height,
    this.anchorY = 0.5,
    this.flip = false,
  });

  /// Point on the body the item is pinned to.
  final Offset anchor;
  final double angle;
  final double width;

  /// Explicit height (clothes are stretched to the body); null = keep aspect.
  final double? height;

  /// Which vertical fraction of the item sits on [anchor] (0 top, 1 bottom).
  final double anchorY;
  final bool flip;
}

Offset _up(double angle) => Offset(math.sin(angle), -math.cos(angle));

double? _headWidth(BodyPoints b) =>
    b.dist(L.leftEar, L.rightEar) ??
    b.dist(L.leftEyeOuter, L.rightEyeOuter).let((d) => d * 1.8);

double? _headAngle(BodyPoints b) =>
    b.lineAngle(L.rightEye, L.leftEye) ?? b.lineAngle(L.rightEar, L.leftEar);

/// Automatic fit for each body part, derived from ML Kit pose landmarks.
List<Placement> placementsFor(BodyPart part, BodyPoints b) {
  switch (part) {
    case BodyPart.head:
      final hw = _headWidth(b);
      final eyes = b.mid(L.leftEye, L.rightEye);
      final ang = _headAngle(b);
      if (hw == null || eyes == null || ang == null) return [];
      return [
        Placement(
          anchor: eyes + _up(ang) * (hw * 0.42),
          angle: ang,
          width: hw * 1.75,
          anchorY: 0.82,
        ),
      ];

    case BodyPart.face:
      final hw = _headWidth(b);
      final nose = b[L.nose];
      final mouth = b.mid(L.leftMouth, L.rightMouth);
      final ang = _headAngle(b);
      if (hw == null || nose == null || mouth == null || ang == null) return [];
      return [
        Placement(
          anchor: Offset.lerp(nose, mouth, 0.55)!,
          angle: ang,
          width: hw * 1.05,
          anchorY: 0.45,
        ),
      ];

    case BodyPart.eyes:
      final hw = _headWidth(b);
      final eyes = b.mid(L.leftEye, L.rightEye);
      final ang = _headAngle(b);
      if (hw == null || eyes == null || ang == null) return [];
      return [
        Placement(anchor: eyes, angle: ang, width: hw * 1.0, anchorY: 0.5),
      ];

    case BodyPart.top:
      final sw = b.dist(L.leftShoulder, L.rightShoulder);
      final sMid = b.mid(L.leftShoulder, L.rightShoulder);
      final ang = b.lineAngle(L.rightShoulder, L.leftShoulder);
      if (sw == null || sMid == null || ang == null) return [];
      final hMid = b.mid(L.leftHip, L.rightHip);
      final torso = hMid != null ? (hMid - sMid).distance : sw * 1.3;
      return [
        Placement(
          anchor: sMid + _up(ang) * (sw * 0.2),
          angle: ang,
          width: sw * 1.6,
          height: torso * 1.25 + sw * 0.2,
          anchorY: 0,
        ),
      ];

    case BodyPart.bottom:
      final hw = b.dist(L.leftHip, L.rightHip);
      final hMid = b.mid(L.leftHip, L.rightHip);
      final ang = b.lineAngle(L.rightHip, L.leftHip);
      if (hw == null || hMid == null || ang == null) return [];
      final ankles = b.mid(L.leftAnkle, L.rightAnkle);
      final knees = b.mid(L.leftKnee, L.rightKnee);
      final leg = ankles != null
          ? (ankles - hMid).distance
          : knees != null
          ? (knees - hMid).distance * 2.05
          : hw * 2.8;
      final spread = math.max(
        b.dist(L.leftAnkle, L.rightAnkle) ?? 0,
        b.dist(L.leftKnee, L.rightKnee) ?? 0,
      );
      return [
        Placement(
          anchor: hMid + _up(ang) * (hw * 0.35),
          angle: ang,
          width: math.max(hw * 2.1, spread + hw * 1.1),
          height: leg * 1.08 + hw * 0.35,
          anchorY: 0,
        ),
      ];

    case BodyPart.skirt:
      final hw = b.dist(L.leftHip, L.rightHip);
      final hMid = b.mid(L.leftHip, L.rightHip);
      if (hw == null || hMid == null) return [];
      final knees = b.mid(L.leftKnee, L.rightKnee);
      final ankles = b.mid(L.leftAnkle, L.rightAnkle);
      // Hip→hem: a little below the knee (knee-length A-line skirt).
      final hem = knees != null
          ? (knees - hMid).distance * 1.2
          : ankles != null
          ? (ankles - hMid).distance * 0.6
          : hw * 2.2;
      final spread = b.dist(L.leftKnee, L.rightKnee) ?? 0;
      // Sample-style skirts: waistband ~47% of the image width, so ~1.4x hip width.
      final width = math.max(hw * 3.0, spread + hw * 1.6);

      final ang = b.lineAngle(L.rightHip, L.leftHip);
      if (ang == null) return [];
      return [
        Placement(
          anchor: hMid + _up(ang) * (hw * 0.4),
          angle: ang,
          width: width,
          height: hem + hw * 0.4,
          anchorY: 0,
        ),
      ];

    case BodyPart.dress:
      final sw = b.dist(L.leftShoulder, L.rightShoulder);
      final sMid = b.mid(L.leftShoulder, L.rightShoulder);
      final ang = b.lineAngle(L.rightShoulder, L.leftShoulder);
      if (sw == null || sMid == null || ang == null) return [];
      final feet = b.mid(L.leftAnkle, L.rightAnkle) ?? b.mid(L.leftHeel, L.rightHeel);
      final knees = b.mid(L.leftKnee, L.rightKnee);
      final hips = b.mid(L.leftHip, L.rightHip);
      // Shoulders → floor; fall back on proportions when the legs are cut off.
      final length = feet != null
          ? (feet - sMid).distance + sw * 0.35
          : knees != null
          ? (knees - sMid).distance * 1.65
          : hips != null
          ? (hips - sMid).distance * 3.3
          : sw * 4.5;
      final spread = b.dist(L.leftAnkle, L.rightAnkle) ?? 0;
      return [
        Placement(
          anchor: sMid + _up(ang) * (sw * 0.12),
          angle: ang,
          // Sample-style dresses: straps ~39% of the image width apart.
          width: math.max(sw * 2.55, spread + sw * 1.3),
          height: length + sw * 0.12,
          anchorY: 0,
        ),
      ];

    case BodyPart.wings:
      final sw = b.dist(L.leftShoulder, L.rightShoulder);
      final sMid = b.mid(L.leftShoulder, L.rightShoulder);
      final ang = b.lineAngle(L.rightShoulder, L.leftShoulder);
      if (sw == null || sMid == null || ang == null) return [];
      final hips = b.mid(L.leftHip, L.rightHip);
      final torso = hips != null ? (hips - sMid).distance : sw * 1.3;
      return [
        Placement(
          anchor: sMid - _up(ang) * (torso * 0.22),
          angle: ang,
          width: sw * 4.2,
          anchorY: 0.45,
        ),
      ];

    case BodyPart.halo:
      final hw = _headWidth(b);
      final eyes = b.mid(L.leftEye, L.rightEye);
      final ang = _headAngle(b);
      if (hw == null || eyes == null || ang == null) return [];
      return [
        Placement(anchor: eyes + _up(ang) * (hw * 1.35), angle: ang, width: hw * 1.3, anchorY: 0.5),
      ];

    case BodyPart.feet:
      final hw = b.dist(L.leftHip, L.rightHip) ??
          b.dist(L.leftAnkle, L.rightAnkle) ??
          b.dist(L.leftKnee, L.rightKnee);
      final out = <Placement>[];
      final hipX = b.mid(L.leftHip, L.rightHip)?.dx ??
          b.mid(L.leftAnkle, L.rightAnkle)?.dx;
      for (final side in [
        (L.leftHeel, L.leftFootIndex, L.leftAnkle),
        (L.rightHeel, L.rightFootIndex, L.rightAnkle),
      ]) {
        final heel = b[side.$1], toe = b[side.$2], ankle = b[side.$3];
        if (heel == null || toe == null) continue;
        final foot = (toe - heel).distance;
        final base = hw ?? foot * 1.4;
        final center = Offset.lerp((heel + toe) / 2, ankle ?? heel, 0.25)!;
        final sideView = foot > base * 0.45;
        final flip = sideView ? toe.dx < heel.dx : (hipX != null && center.dx < hipX);
        var ang = 0.0;
        if (sideView) {
          var v = toe - heel;
          if (v.dx < 0) v = -v;
          ang = math.atan2(v.dy, v.dx);
        }
        out.add(
          Placement(
            anchor: center,
            angle: ang,
            width: math.max(foot * 1.45, base * 0.75),
            anchorY: 0.55,
            flip: flip,
          ),
        );
      }
      return out;
  }
}

const _focusPoints = <BodyPart, List<PoseLandmarkType>>{
  BodyPart.head: [L.leftEar, L.rightEar, L.leftEye, L.rightEye, L.nose],
  BodyPart.face: [L.nose, L.leftMouth, L.rightMouth],
  BodyPart.eyes: [L.leftEyeOuter, L.leftEye, L.leftEyeInner, L.rightEyeInner, L.rightEye, L.rightEyeOuter],
  BodyPart.top: [L.leftShoulder, L.rightShoulder, L.leftHip, L.rightHip],
  BodyPart.bottom: [L.leftHip, L.rightHip, L.leftKnee, L.rightKnee, L.leftAnkle, L.rightAnkle],
  BodyPart.skirt: [L.leftHip, L.rightHip, L.leftKnee, L.rightKnee],
  BodyPart.dress: [L.leftShoulder, L.rightShoulder, L.leftHip, L.rightHip, L.leftAnkle, L.rightAnkle],
  BodyPart.wings: [L.leftShoulder, L.rightShoulder, L.leftElbow, L.rightElbow],
  BodyPart.halo: [L.leftEar, L.rightEar, L.leftEye, L.rightEye],
  BodyPart.feet: [L.leftAnkle, L.rightAnkle, L.leftHeel, L.rightHeel, L.leftFootIndex, L.rightFootIndex],
};

/// ML Kit person mask (confidence as alpha) covering the whole frame.
class PersonMask {
  PersonMask(this.image, {this.mirror = false});
  final ui.Image image;

  /// Mask comes from the unmirrored frame; flip it for a mirrored view.
  final bool mirror;

  void draw(Canvas canvas, Rect dst, BlendMode mode) {
    canvas.save();
    if (mirror) {
      canvas.translate(dst.left + dst.right, 0);
      canvas.scale(-1, 1);
    }
    canvas.drawImageRect(
      image,
      Rect.fromLTWH(0, 0, image.width.toDouble(), image.height.toDouble()),
      dst,
      Paint()
        ..blendMode = mode
        ..filterQuality = FilterQuality.medium,
    );
    canvas.restore();
  }
}

/// Builds a [PersonMask] image from ML Kit confidences (row-major, w x h).
Future<ui.Image> maskImage(List<double> confidences, int w, int h) {
  final px = Uint8List(w * h * 4);
  for (var i = 0; i < w * h; i++) {
    // Steepen the soft edge a little so the backdrop does not ghost through.
    final a = (((confidences[i] - 0.25) / 0.5).clamp(0.0, 1.0) * 255).round();
    final o = i * 4;
    px[o] = px[o + 1] = px[o + 2] = px[o + 3] = a;
  }
  final c = Completer<ui.Image>();
  ui.decodeImageFromPixels(px, w, h, ui.PixelFormat.rgba8888, c.complete);
  return c.future;
}

/// Back-to-front drawing order; wings sit behind the person.
const _order = [
  BodyPart.wings,
  BodyPart.bottom,
  BodyPart.skirt,
  BodyPart.dress,
  BodyPart.top,
  BodyPart.feet,
  BodyPart.face,
  BodyPart.eyes,
  BodyPart.head,
  BodyPart.halo,
];

/// Draws the backdrop (outside the person) and every worn item onto
/// [canvas] whose coordinate system is image pixels.
void paintOutfit(
  Canvas canvas,
  BodyPoints body,
  Map<BodyPart, ui.Image> items,
  Map<BodyPart, PartAdjust> adjust, {
  PersonMask? mask,
  ui.Image? backdrop,
}) {
  final full = Offset.zero & body.imageSize;
  final paint = Paint()
    ..filterQuality = FilterQuality.medium
    ..isAntiAlias = true;
  if (backdrop != null && mask != null) {
    canvas.saveLayer(full, Paint());
    canvas.drawImageRect(backdrop, _coverSrc(backdrop, full.size), full, paint);
    mask.draw(canvas, full, BlendMode.dstOut);
    canvas.restore();
  }
  if (body.isEmpty) return;
  for (final part in _order) {
    final image = items[part];
    if (image == null) continue;
    final behind = part == BodyPart.wings && mask != null;
    if (behind) canvas.saveLayer(full, Paint());
    _drawPart(canvas, part, image, body, adjust[part] ?? PartAdjust(), paint);
    if (behind) {
      mask.draw(canvas, full, BlendMode.dstOut);
      canvas.restore();
    }
  }
}

void _drawPart(Canvas canvas, BodyPart part, ui.Image image, BodyPoints body, PartAdjust adj, Paint paint) {
  final ar = image.height / image.width;
  for (final pl in placementsFor(part, body)) {
    final w = pl.width * adj.scale * adj.width;
    final h = (pl.height ?? pl.width * ar) * adj.scale * adj.height;
    final rect = Rect.fromLTWH(-w / 2 + adj.dx * w, -pl.anchorY * h + adj.dy * h, w, h);
    canvas.save();
    canvas.translate(pl.anchor.dx, pl.anchor.dy);
    canvas.rotate(pl.angle);
    canvas.translate(rect.center.dx, rect.center.dy);
    if (pl.flip != adj.flip) canvas.scale(-1, 1);
    canvas.drawImageRect(
      image,
      Rect.fromLTWH(0, 0, image.width.toDouble(), image.height.toDouble()),
      Rect.fromCenter(center: Offset.zero, width: w, height: h),
      paint,
    );
    canvas.restore();
  }
}

/// Source rect that crops [img] to fill [dst] (BoxFit.cover).
Rect _coverSrc(ui.Image img, Size dst) {
  final iw = img.width.toDouble(), ih = img.height.toDouble();
  final s = math.max(dst.width / iw, dst.height / ih);
  final w = dst.width / s, h = dst.height / s;
  return Rect.fromLTWH((iw - w) / 2, (ih - h) / 2, w, h);
}

/// Live overlay above the camera preview. Its size equals the upright
/// preview size; it scales image-pixel coordinates onto it.
class OutfitOverlayPainter extends CustomPainter {
  OutfitOverlayPainter({
    required this.body,
    required this.items,
    required this.adjust,
    required this.focus,
    required this.pulse,
    required this.revision,
    this.mask,
    this.backdrop,
  }) : super(repaint: pulse);

  final PersonMask? mask;
  final ui.Image? backdrop;

  final BodyPoints body;
  final Map<BodyPart, ui.Image> items;
  final Map<BodyPart, PartAdjust> adjust;
  final BodyPart? focus;
  final Animation<double> pulse;

  /// Bumped whenever worn items or adjustments change.
  final int revision;

  @override
  void paint(Canvas canvas, Size size) {
    if (body.imageSize.isEmpty) return;
    canvas.save();
    canvas.scale(
      size.width / body.imageSize.width,
      size.height / body.imageSize.height,
    );
    paintOutfit(canvas, body, items, adjust, mask: mask, backdrop: backdrop);
    if (focus != null && !body.isEmpty) _paintFocus(canvas);
    canvas.restore();
  }

  void _paintFocus(Canvas canvas) {
    final pts = [
      for (final t in _focusPoints[focus]!)
        if (body[t] != null) body[t]!,
    ];
    if (pts.isEmpty) return;
    final unit = body.imageSize.width / 100;
    var rect = Rect.fromPoints(pts.first, pts.first);
    for (final p in pts) {
      rect = rect.expandToInclude(Rect.fromPoints(p, p));
    }
    rect = rect.inflate(unit * 4);
    final t = pulse.value;
    final glow = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = unit * (0.6 + 0.4 * t)
      ..color = Colors.white.withValues(alpha: 0.55 + 0.35 * t)
      ..maskFilter = MaskFilter.blur(BlurStyle.normal, unit * 0.6);
    canvas.drawRRect(
      RRect.fromRectAndRadius(rect, Radius.circular(unit * 4)),
      glow,
    );
    final dot = Paint()..color = const Color(0xFF5BE7FF).withValues(alpha: 0.9);
    for (final p in pts) {
      canvas.drawCircle(p, unit * (0.8 + 0.3 * t), dot);
    }
  }

  @override
  bool shouldRepaint(OutfitOverlayPainter old) =>
      old.body != body ||
      old.items != items ||
      old.focus != focus ||
      old.revision != revision ||
      old.mask != mask ||
      old.backdrop != backdrop;
}

extension _Let<T> on T? {
  R? let<R>(R Function(T) f) {
    final v = this;
    return v == null ? null : f(v);
  }
}

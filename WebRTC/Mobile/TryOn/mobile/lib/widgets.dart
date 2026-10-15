import 'dart:io';
import 'dart:ui';

import 'package:flutter/material.dart';

const kAccent = Color(0xFF7C5CFF);
const kAccent2 = Color(0xFF34D3FF);
const kPink = Color(0xFFFF5FA2);

/// Frosted-glass panel (iOS style).
class Glass extends StatelessWidget {
  const Glass({
    super.key,
    required this.child,
    this.radius = 22,
    this.padding = EdgeInsets.zero,
    this.opacity = 0.16,
  });

  final Widget child;
  final double radius;
  final EdgeInsetsGeometry padding;
  final double opacity;

  @override
  Widget build(BuildContext context) {
    return ClipRRect(
      borderRadius: BorderRadius.circular(radius),
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: 18, sigmaY: 18),
        child: Container(
          padding: padding,
          decoration: BoxDecoration(
            color: Colors.white.withValues(alpha: opacity),
            borderRadius: BorderRadius.circular(radius),
            border: Border.all(color: Colors.white.withValues(alpha: 0.22)),
          ),
          child: child,
        ),
      ),
    );
  }
}

/// Checkerboard behind transparent product images.
class Checker extends StatelessWidget {
  const Checker({super.key, required this.child, this.radius = 14});
  final Widget child;
  final double radius;

  @override
  Widget build(BuildContext context) {
    return ClipRRect(
      borderRadius: BorderRadius.circular(radius),
      child: CustomPaint(painter: _CheckerPainter(), child: child),
    );
  }
}

class _CheckerPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    canvas.drawRect(Offset.zero & size, Paint()..color = const Color(0xFFF2F2F7));
    final p = Paint()..color = const Color(0xFFDCDCE4);
    const s = 8.0;
    for (var y = 0.0; y < size.height; y += s) {
      for (var x = ((y / s).floor().isOdd ? s : 0.0); x < size.width; x += s * 2) {
        canvas.drawRect(Rect.fromLTWH(x, y, s, s), p);
      }
    }
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}

Widget productThumb(String path, {double size = 64, BoxFit fit = BoxFit.contain}) {
  return Checker(
    child: SizedBox(
      width: size,
      height: size,
      child: Padding(
        padding: const EdgeInsets.all(4),
        child: Image.file(File(path), fit: fit, gaplessPlayback: true, cacheWidth: (size * 3).round()),
      ),
    ),
  );
}

/// Small faded button shown while the UI is hidden; brings it back.
/// Turns red while a video is recording so that state is never invisible.
class ShowUiButton extends StatelessWidget {
  const ShowUiButton({super.key, required this.onTap, this.recording = false});
  final VoidCallback onTap;
  final bool recording;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Opacity(
        opacity: recording ? 0.9 : 0.55,
        child: Container(
          width: 40,
          height: 40,
          decoration: BoxDecoration(
            shape: BoxShape.circle,
            color: recording ? const Color(0xFFFF3B30) : Colors.black38,
            border: Border.all(color: Colors.white54),
          ),
          child: const Icon(Icons.visibility_outlined, color: Colors.white, size: 20),
        ),
      ),
    );
  }
}

void toast(BuildContext context, String msg) {
  ScaffoldMessenger.of(context)
    ..hideCurrentSnackBar()
    ..showSnackBar(
      SnackBar(
        content: Text(msg),
        behavior: SnackBarBehavior.floating,
        duration: const Duration(seconds: 2),
      ),
    );
}

Future<bool> confirm(BuildContext context, String title, String body) async {
  final r = await showDialog<bool>(
    context: context,
    builder: (c) => AlertDialog(
      title: Text(title),
      content: Text(body),
      actions: [
        TextButton(onPressed: () => Navigator.pop(c, false), child: const Text('Huỷ')),
        FilledButton(
          style: FilledButton.styleFrom(backgroundColor: Colors.redAccent),
          onPressed: () => Navigator.pop(c, true),
          child: const Text('Xoá'),
        ),
      ],
    ),
  );
  return r ?? false;
}

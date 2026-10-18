import 'dart:ui';

import 'package:flutter/material.dart';

const kAccent = Color(0xFF6E5AF0);
const kPink = Color(0xFFFF6E96);

class Backdrop extends StatelessWidget {
  const Backdrop({super.key, required this.child});
  final Widget child;

  @override
  Widget build(BuildContext context) => DecoratedBox(
    decoration: const BoxDecoration(
      gradient: LinearGradient(
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
        colors: [Color(0xFFFFEAD6), Color(0xFFFFE0EC), Color(0xFFE6E2FF)],
      ),
    ),
    child: child,
  );
}

class Glass extends StatelessWidget {
  const Glass({super.key, required this.child, this.padding = const EdgeInsets.all(14), this.onTap, this.onLongPress});
  final Widget child;
  final EdgeInsetsGeometry padding;
  final VoidCallback? onTap;
  final VoidCallback? onLongPress;

  @override
  Widget build(BuildContext context) => ClipRRect(
    borderRadius: BorderRadius.circular(22),
    child: BackdropFilter(
      filter: ImageFilter.blur(sigmaX: 20, sigmaY: 20),
      child: Material(
        color: Colors.white.withValues(alpha: 0.6),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(22),
          side: BorderSide(color: Colors.white.withValues(alpha: 0.9)),
        ),
        child: InkWell(onTap: onTap, onLongPress: onLongPress, child: Padding(padding: padding, child: child)),
      ),
    ),
  );
}

/// Translucent iOS-style pill button.
class GlassButton extends StatelessWidget {
  const GlassButton({super.key, required this.label, this.icon, this.onPressed, this.tint = kAccent});
  final String label;
  final IconData? icon;
  final VoidCallback? onPressed;
  final Color tint;

  @override
  Widget build(BuildContext context) {
    final color = onPressed == null ? Colors.black38 : tint;
    return ClipRRect(
      borderRadius: BorderRadius.circular(40),
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: 18, sigmaY: 18),
        child: Material(
          color: Color.alphaBlend(tint.withValues(alpha: 0.08), Colors.white.withValues(alpha: 0.55)),
          shape: StadiumBorder(side: BorderSide(color: Colors.white.withValues(alpha: 0.95))),
          child: InkWell(
            onTap: onPressed,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  if (icon != null) ...[Icon(icon, color: color, size: 20), const SizedBox(width: 8)],
                  Text(label, style: TextStyle(color: color, fontWeight: FontWeight.w600)),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

void toast(BuildContext context, String msg) => ScaffoldMessenger.of(context)
  ..hideCurrentSnackBar()
  ..showSnackBar(SnackBar(content: Text(msg), behavior: SnackBarBehavior.floating));

Future<bool> confirm(BuildContext context, String title, String body) async =>
    await showDialog<bool>(
      context: context,
      builder: (c) => AlertDialog(
        title: Text(title),
        content: Text(body),
        actions: [
          TextButton(onPressed: () => Navigator.pop(c, false), child: const Text('Huỷ')),
          TextButton(
            onPressed: () => Navigator.pop(c, true),
            child: const Text('Xoá', style: TextStyle(color: Colors.redAccent, fontWeight: FontWeight.w700)),
          ),
        ],
      ),
    ) ??
    false;

const palette = <int>[
  0xFFFFFFFF, 0xFFF5F0E6, 0xFFFFF6E5, 0xFF1C1C1E, 0xFF8E8E93, 0xFFFF6B9A, 0xFFFFC2D6, 0xFFFF3B30,
  0xFFFF9500, 0xFFFFCC00, 0xFFD4AF37, 0xFF34C759, 0xFFA8E6CF, 0xFF5AC8FA, 0xFF007AFF, 0xFFAF52DE,
];

/// Swatches + "custom" (HSL sliders).
class ColorRow extends StatelessWidget {
  const ColorRow({super.key, required this.value, required this.onChanged});
  final int value;
  final ValueChanged<int> onChanged;

  @override
  Widget build(BuildContext context) {
    Widget dot(int c, {Widget? child, VoidCallback? onTap}) => GestureDetector(
      onTap: onTap ?? () => onChanged(c),
      child: Container(
        width: 32,
        height: 32,
        margin: const EdgeInsets.only(right: 8),
        decoration: BoxDecoration(
          color: Color(c),
          shape: BoxShape.circle,
          border: Border.all(color: value == c ? kAccent : Colors.black12, width: value == c ? 3 : 1),
        ),
        child: child,
      ),
    );
    return SizedBox(
      height: 36,
      child: ListView(
        scrollDirection: Axis.horizontal,
        children: [
          for (final c in palette) dot(c),
          dot(
            0xFFFFFFFF,
            child: const Icon(Icons.palette_outlined, size: 18, color: kAccent),
            onTap: () async {
              final c = await pickColor(context, value);
              if (c != null) onChanged(c);
            },
          ),
        ],
      ),
    );
  }
}

Future<int?> pickColor(BuildContext context, int initial) {
  var hsl = HSLColor.fromColor(Color(initial));
  return showDialog<int>(
    context: context,
    builder: (c) => StatefulBuilder(
      builder: (c, set) => AlertDialog(
        title: const Text('Chọn màu'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(height: 48, decoration: BoxDecoration(color: hsl.toColor(), borderRadius: BorderRadius.circular(12))),
            const SizedBox(height: 12),
            _slider('Sắc', hsl.hue, 0, 360, (v) => set(() => hsl = hsl.withHue(v))),
            _slider('Độ đậm', hsl.saturation, 0, 1, (v) => set(() => hsl = hsl.withSaturation(v))),
            _slider('Sáng', hsl.lightness, 0, 1, (v) => set(() => hsl = hsl.withLightness(v))),
          ],
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(c), child: const Text('Huỷ')),
          TextButton(onPressed: () => Navigator.pop(c, hsl.toColor().toARGB32()), child: const Text('Chọn')),
        ],
      ),
    ),
  );
}

Widget _slider(String label, double v, double min, double max, ValueChanged<double> onChanged) => Row(
  children: [
    SizedBox(width: 56, child: Text(label)),
    Expanded(child: Slider(value: v.clamp(min, max), min: min, max: max, onChanged: onChanged)),
  ],
);

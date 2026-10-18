import 'dart:io';
import 'dart:ui';

import 'package:flutter/material.dart';

import 'models.dart';

/// Theme gradient or the user's background photo (optionally blurred).
class ThemedBackground extends StatelessWidget {
  const ThemedBackground({super.key, required this.config, required this.child});
  final AppConfig config;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final t = config.palette;
    final bg = config.bgImage;
    return Stack(
      fit: StackFit.expand,
      children: [
        DecoratedBox(
          decoration: BoxDecoration(
            gradient: LinearGradient(begin: Alignment.topLeft, end: Alignment.bottomRight, colors: t.bg),
          ),
        ),
        if (bg != null)
          ImageFiltered(
            imageFilter: ImageFilter.blur(sigmaX: config.bgBlur, sigmaY: config.bgBlur, tileMode: TileMode.decal),
            child: Image.file(File(bg), fit: BoxFit.cover, gaplessPlayback: true),
          ),
        if (bg != null) ColoredBox(color: (t.dark ? Colors.black : Colors.white).withValues(alpha: 0.12)),
        child,
      ],
    );
  }
}

/// Frosted card in the current theme.
class Glass extends StatelessWidget {
  const Glass({super.key, required this.theme, required this.child, this.padding = const EdgeInsets.all(14), this.radius = 22, this.onTap});
  final NewsTheme theme;
  final Widget child;
  final EdgeInsetsGeometry padding;
  final double radius;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return ClipRRect(
      borderRadius: BorderRadius.circular(radius),
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: 22, sigmaY: 22),
        child: Material(
          color: theme.card,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(radius), side: BorderSide(color: theme.border)),
          child: InkWell(onTap: onTap, child: Padding(padding: padding, child: child)),
        ),
      ),
    );
  }
}

void toast(BuildContext context, String msg) {
  ScaffoldMessenger.of(context)
    ..hideCurrentSnackBar()
    ..showSnackBar(SnackBar(content: Text(msg), behavior: SnackBarBehavior.floating));
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

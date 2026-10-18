import 'dart:ui';

import 'package:flutter/material.dart';

import 'models.dart';
import 'native.dart';

const kAccent = Color(0xFF6C5CE7);
const kAccent2 = Color(0xFF00B4D8);

/// Soft iOS-like gradient behind every page.
class Backdrop extends StatelessWidget {
  const Backdrop({super.key, required this.child});
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [Color(0xFFE9E4FF), Color(0xFFDDF3FF), Color(0xFFFFE8F3)],
        ),
      ),
      child: child,
    );
  }
}

/// Frosted white card.
class Glass extends StatelessWidget {
  const Glass({super.key, required this.child, this.padding = const EdgeInsets.all(14), this.radius = 22, this.onTap});
  final Widget child;
  final EdgeInsetsGeometry padding;
  final double radius;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return ClipRRect(
      borderRadius: BorderRadius.circular(radius),
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: 20, sigmaY: 20),
        child: Material(
          color: Colors.white.withValues(alpha: 0.62),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(radius),
            side: BorderSide(color: Colors.white.withValues(alpha: 0.9)),
          ),
          child: InkWell(onTap: onTap, child: Padding(padding: padding, child: child)),
        ),
      ),
    );
  }
}

void toast(BuildContext context, String msg, {SnackBarAction? action}) {
  ScaffoldMessenger.of(context)
    ..hideCurrentSnackBar()
    ..showSnackBar(SnackBar(content: Text(msg), behavior: SnackBarBehavior.floating, action: action));
}

Future<bool> confirm(BuildContext context, String title, String body, {String ok = 'Xoá'}) async {
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
          child: Text(ok),
        ),
      ],
    ),
  );
  return r ?? false;
}

/// Small speaker button; reads [text] in [lang]'s TTS voice.
class SpeakButton extends StatelessWidget {
  const SpeakButton({super.key, required this.text, required this.lang, this.size = 22});
  final String text;
  final String lang;
  final double size;

  @override
  Widget build(BuildContext context) {
    final l = Lang.of(lang);
    if (l.tts.isEmpty) return SizedBox(width: size + 16);
    return IconButton(
      visualDensity: VisualDensity.compact,
      iconSize: size,
      color: kAccent,
      tooltip: 'Đọc (${l.label})',
      icon: const Icon(Icons.volume_up_rounded),
      onPressed: () async {
        final r = await Native.speak(text, l.tts);
        if (!context.mounted) return;
        if (r == 'missing') {
          toast(
            context,
            'Máy chưa có giọng đọc ${l.label}',
            action: SnackBarAction(label: 'Cài giọng', onPressed: Native.installTts),
          );
        } else if (r == 'error') {
          toast(context, 'Không đọc được (TTS lỗi)');
        }
      },
    );
  }
}

import 'dart:io';

import 'package:flutter/material.dart';

import 'models.dart';
import 'widgets.dart';

/// Glass row with one round button per body part, showing what is worn.
class PartBar extends StatelessWidget {
  const PartBar({super.key, required this.store, required this.focus, required this.onTap});

  final CartStore store;
  final BodyPart? focus;
  final void Function(BodyPart) onTap;

  @override
  Widget build(BuildContext context) {
    return Glass(
      radius: 26,
      padding: const EdgeInsets.symmetric(vertical: 10),
      // 10 parts do not fit a phone width: scroll sideways.
      child: SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 6),
        child: Row(
          children: [
            for (final p in BodyPart.values)
              Padding(padding: const EdgeInsets.symmetric(horizontal: 5), child: _button(p)),
          ],
        ),
      ),
    );
  }

  Widget _button(BodyPart part) {
    final worn = store.wornItem(part);
    final active = focus == part;
    return GestureDetector(
      onTap: () => onTap(part),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          AnimatedContainer(
            duration: const Duration(milliseconds: 200),
            width: 46,
            height: 46,
            padding: const EdgeInsets.all(2.5),
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              gradient: worn != null || active
                  ? const LinearGradient(colors: [kPink, kAccent, kAccent2])
                  : null,
              color: worn != null || active ? null : Colors.white24,
            ),
            child: ClipOval(
              child: Container(
                color: worn != null ? Colors.white : Colors.black26,
                child: worn != null
                    ? Padding(
                        padding: const EdgeInsets.all(4),
                        child: Image.file(File(worn.path), fit: BoxFit.contain, cacheWidth: 120, gaplessPlayback: true),
                      )
                    : Icon(part.icon, color: Colors.white, size: 20),
              ),
            ),
          ),
          const SizedBox(height: 4),
          Text(
            part.label,
            style: TextStyle(
              color: Colors.white,
              fontSize: 11,
              fontWeight: active || worn != null ? FontWeight.w700 : FontWeight.w500,
            ),
          ),
        ],
      ),
    );
  }
}

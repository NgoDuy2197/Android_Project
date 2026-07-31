import 'package:flutter/material.dart';

/// Big toggle for the live "walkie-talkie" mode. Present on both roles.
class TalkToggle extends StatelessWidget {
  final bool talking;
  final bool enabled;
  final VoidCallback onPressed;
  const TalkToggle({
    super.key,
    required this.talking,
    required this.enabled,
    required this.onPressed,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return SizedBox(
      width: double.infinity,
      child: FilledButton.icon(
        onPressed: enabled ? onPressed : null,
        icon: Icon(talking ? Icons.mic : Icons.mic_none, size: 26),
        label: Text(
          talking ? 'ĐANG NÓI — bấm để dừng' : 'Nói trực tiếp (bộ đàm)',
          style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
        ),
        style: FilledButton.styleFrom(
          padding: const EdgeInsets.symmetric(vertical: 18),
          backgroundColor: talking ? cs.error : cs.primary,
          foregroundColor: talking ? cs.onError : cs.onPrimary,
        ),
      ),
    );
  }
}

/// Small "receiving voice" indicator.
class ReceivingBadge extends StatelessWidget {
  final bool active;
  const ReceivingBadge({super.key, required this.active});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return AnimatedOpacity(
      opacity: active ? 1 : 0,
      duration: const Duration(milliseconds: 200),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(Icons.volume_up, color: cs.primary),
          const SizedBox(width: 8),
          const Text('Đang nhận giọng nói…'),
        ],
      ),
    );
  }
}

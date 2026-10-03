import 'package:flutter/material.dart';

import 'sfx.dart';
import 'sound_settings.dart';

/// Lets the user choose, per game event: bundled default / off / custom file,
/// a per-event volume, and a master volume + mute.
class SoundSettingsScreen extends StatelessWidget {
  final Sfx sfx;
  const SoundSettingsScreen({super.key, required this.sfx});

  SoundSettings get _s => sfx.settings;

  Future<void> _confirmReset(BuildContext context) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Khôi phục mặc định?'),
        content: const Text(
            'Tất cả âm thanh sẽ trở về âm mặc định và xoá các file đã chọn.'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('Huỷ')),
          FilledButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('Khôi phục')),
        ],
      ),
    );
    if (ok == true) {
      await _s.resetAll();
      sfx.clearFallbacks();
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Âm thanh'),
        actions: [
          IconButton(
            tooltip: 'Khôi phục mặc định',
            icon: const Icon(Icons.restore),
            onPressed: () => _confirmReset(context),
          ),
        ],
      ),
      body: ListenableBuilder(
        listenable: _s,
        builder: (context, _) => ListView(
          padding: const EdgeInsets.fromLTRB(14, 8, 14, 24),
          children: [
            _card(
              child: Column(
                children: [
                  SwitchListTile(
                    contentPadding: EdgeInsets.zero,
                    title: const Text('Tắt toàn bộ âm thanh',
                        style: TextStyle(fontWeight: FontWeight.w600)),
                    secondary: Icon(
                        _s.muted ? Icons.volume_off : Icons.volume_up),
                    value: _s.muted,
                    onChanged: _s.setMuted,
                  ),
                  Row(
                    children: [
                      const Text('Âm lượng chung',
                          style:
                              TextStyle(fontSize: 13, color: Colors.white70)),
                      Expanded(
                        child: Slider(
                          value: _s.masterVolume,
                          onChanged: _s.muted ? null : _s.setMasterVolume,
                        ),
                      ),
                      SizedBox(
                        width: 40,
                        child: Text('${(_s.masterVolume * 100).round()}%',
                            textAlign: TextAlign.end,
                            style: const TextStyle(fontSize: 13)),
                      ),
                    ],
                  ),
                ],
              ),
            ),
            const Padding(
              padding: EdgeInsets.fromLTRB(4, 14, 4, 6),
              child: Text(
                  'Mỗi sự kiện: dùng âm mặc định, tắt, hoặc chọn file âm thanh '
                  'trên máy (mp3, wav, ogg…). Bấm ▶ để nghe thử.',
                  style: TextStyle(fontSize: 12, color: Colors.white54)),
            ),
            for (final e in SfxEvent.values) _EventTile(sfx: sfx, event: e),
          ],
        ),
      ),
    );
  }
}

Widget _card({required Widget child}) => Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.fromLTRB(14, 10, 14, 10),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.06),
        borderRadius: BorderRadius.circular(14),
      ),
      child: child,
    );

class _EventTile extends StatelessWidget {
  final Sfx sfx;
  final SfxEvent event;
  const _EventTile({required this.sfx, required this.event});

  Future<void> _pick(BuildContext context) async {
    final ok = await sfx.settings.pickCustom(event);
    if (ok) {
      sfx.clearFallbacks();
      sfx.preview(event);
    } else if (context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
          content: Text('Không chọn được file âm thanh.'),
          duration: Duration(seconds: 2)));
    }
  }

  Future<void> _onMode(BuildContext context, SoundMode m) async {
    final s = sfx.settings;
    if (m == SoundMode.custom && s.of(event).path == null) {
      await _pick(context); // no file yet -> pick one first
      return;
    }
    await s.setMode(event, m);
    if (m != SoundMode.off) sfx.preview(event);
  }

  @override
  Widget build(BuildContext context) {
    final c = sfx.settings.of(event);
    final off = c.mode == SoundMode.off;
    final fallback = sfx.isFallingBack(event);
    return _card(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Text(event.emoji, style: const TextStyle(fontSize: 22)),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(event.label,
                        style: const TextStyle(
                            fontSize: 15, fontWeight: FontWeight.w700)),
                    Text(event.description,
                        style: const TextStyle(
                            fontSize: 12, color: Colors.white54)),
                  ],
                ),
              ),
              IconButton.filledTonal(
                tooltip: 'Nghe thử',
                onPressed: off ? null : () => sfx.preview(event),
                icon: const Icon(Icons.play_arrow),
              ),
            ],
          ),
          const SizedBox(height: 8),
          SizedBox(
            width: double.infinity,
            child: SegmentedButton<SoundMode>(
              showSelectedIcon: false,
              segments: const [
                ButtonSegment(value: SoundMode.standard, label: Text('Mặc định')),
                ButtonSegment(value: SoundMode.off, label: Text('Tắt')),
                ButtonSegment(value: SoundMode.custom, label: Text('Tuỳ chọn')),
              ],
              selected: {c.mode},
              onSelectionChanged: (v) => _onMode(context, v.first),
            ),
          ),
          if (c.mode == SoundMode.custom)
            Padding(
              padding: const EdgeInsets.only(top: 6),
              child: Row(
                children: [
                  Icon(fallback ? Icons.warning_amber : Icons.audio_file,
                      size: 18,
                      color: fallback
                          ? const Color(0xFFF5A623)
                          : Colors.white54),
                  const SizedBox(width: 6),
                  Expanded(
                    child: Text(
                      fallback
                          ? 'File lỗi/không còn — đang dùng âm mặc định'
                          : (c.name ?? 'File tuỳ chọn'),
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontSize: 12),
                    ),
                  ),
                  TextButton.icon(
                    onPressed: () => _pick(context),
                    icon: const Icon(Icons.folder_open, size: 18),
                    label: const Text('Chọn file'),
                  ),
                ],
              ),
            ),
          if (!off)
            Row(
              children: [
                const Icon(Icons.volume_down, size: 18, color: Colors.white54),
                Expanded(
                  child: Slider(
                    value: c.volume,
                    onChanged: (v) => sfx.settings.setVolume(event, v),
                    onChangeEnd: (_) => sfx.preview(event),
                  ),
                ),
                SizedBox(
                  width: 40,
                  child: Text('${(c.volume * 100).round()}%',
                      textAlign: TextAlign.end,
                      style: const TextStyle(fontSize: 12)),
                ),
              ],
            ),
        ],
      ),
    );
  }
}

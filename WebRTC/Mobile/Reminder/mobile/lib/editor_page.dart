import 'dart:async';
import 'dart:io';

import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';

import 'main.dart';
import 'models.dart';
import 'native.dart';

class EditorPage extends StatefulWidget {
  const EditorPage({super.key, this.reminder});
  final Reminder? reminder;

  /// Popped instead of a Reminder when the user deletes it.
  static const deleted = 'deleted';

  @override
  State<EditorPage> createState() => _EditorPageState();
}

class _EditorPageState extends State<EditorPage> {
  late final Reminder r;
  late final TextEditingController _title;
  late final TextEditingController _note;
  bool _recording = false;
  bool _playing = false;
  int _recMs = 0;
  Timer? _recTimer;
  String? _pendingRecording; // new file not yet saved

  @override
  void initState() {
    super.initState();
    final now = DateTime.now();
    final nextHour = DateTime(now.year, now.month, now.day, now.hour + 1);
    r = widget.reminder ??
        Reminder(
          id: 'r${now.microsecondsSinceEpoch}',
          anchor: nextHour.millisecondsSinceEpoch,
          at: nextHour.millisecondsSinceEpoch,
        );
    if (r.anchor == 0) r.anchor = nextHour.millisecondsSinceEpoch;
    if (r.at == 0) r.at = nextHour.millisecondsSinceEpoch;
    _title = TextEditingController(text: r.title);
    _note = TextEditingController(text: r.note);
  }

  @override
  void dispose() {
    _recTimer?.cancel();
    if (_recording) Native.stopRecording();
    if (_playing) Native.stopPlay();
    // Unsaved fresh recording: throw it away.
    final p = _pendingRecording;
    if (p != null && p != r.recording) File(p).delete().ignore();
    super.dispose();
  }

  Future<void> _toggleRecord() async {
    if (_recording) {
      _recTimer?.cancel();
      final ms = await Native.stopRecording();
      setState(() {
        _recording = false;
        _recMs = ms;
      });
      return;
    }
    final dir = await ReminderStore.recordingsDir();
    final path = '${dir.path}/${r.id}_${DateTime.now().millisecondsSinceEpoch}.m4a';
    if (!await Native.startRecording(path)) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Cần quyền micro để ghi âm')));
      }
      return;
    }
    if (_pendingRecording != null && _pendingRecording != r.recording) File(_pendingRecording!).delete().ignore();
    _pendingRecording = path;
    final started = DateTime.now();
    _recTimer = Timer.periodic(const Duration(milliseconds: 200), (_) {
      setState(() => _recMs = DateTime.now().difference(started).inMilliseconds);
    });
    setState(() {
      _recording = true;
      r.useRecording = true;
    });
  }

  String? get _recordingPath => _pendingRecording ?? (r.recording.isEmpty ? null : r.recording);

  Future<void> _togglePlay() async {
    final p = _recordingPath;
    if (p == null) return;
    if (_playing) {
      await Native.stopPlay();
    } else {
      await Native.play(p);
      Future.delayed(Duration(milliseconds: _recMs > 0 ? _recMs + 300 : 4000), () {
        if (mounted) setState(() => _playing = false);
      });
    }
    setState(() => _playing = !_playing);
  }

  void _save() {
    if (_recording) return;
    r.title = _title.text.trim();
    r.note = _note.text.trim();
    if (_pendingRecording != null) {
      if (r.recording.isNotEmpty && r.recording != _pendingRecording) File(r.recording).delete().ignore();
      r.recording = _pendingRecording!;
      _pendingRecording = null;
    }
    if (r.useRecording && r.recording.isEmpty) r.useRecording = false;
    if (r.type == RType.countdown) r.startedAt = DateTime.now().millisecondsSinceEpoch;
    r.enabled = true;
    Navigator.pop(context, r);
  }

  Future<void> _pickDateTime(int current, void Function(int) set) async {
    var picked = DateTime.fromMillisecondsSinceEpoch(current);
    await showCupertinoModalPopup<void>(
      context: context,
      builder: (c) => Container(
        height: 320,
        color: CupertinoColors.systemBackground.resolveFrom(c),
        child: Column(
          children: [
            Align(
              alignment: Alignment.centerRight,
              child: CupertinoButton(child: const Text('Xong'), onPressed: () => Navigator.pop(c)),
            ),
            Expanded(
              child: CupertinoDatePicker(
                initialDateTime: picked,
                use24hFormat: true,
                onDateTimeChanged: (v) => picked = v,
              ),
            ),
          ],
        ),
      ),
    );
    setState(() => set(DateTime(picked.year, picked.month, picked.day, picked.hour, picked.minute).millisecondsSinceEpoch));
  }

  @override
  Widget build(BuildContext context) {
    final t = Theme.of(context);
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.reminder == null ? 'Nhắc mới' : 'Sửa nhắc nhở', style: const TextStyle(fontWeight: FontWeight.w700)),
        actions: [
          if (widget.reminder != null)
            IconButton(
              tooltip: 'Xoá nhắc nhở',
              icon: const Icon(Icons.delete_outline_rounded, color: Colors.redAccent),
              onPressed: () async {
                if (await confirmDelete(context, r.title.isEmpty ? 'Nhắc nhở' : r.title) && context.mounted) {
                  Navigator.pop(context, EditorPage.deleted);
                }
              },
            ),
          TextButton(onPressed: _recording ? null : _save, child: const Text('Lưu', style: TextStyle(fontSize: 17, fontWeight: FontWeight.w700))),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(16, 4, 16, 40),
        children: [
          Glass(
            child: Column(
              children: [
                TextField(
                  controller: _title,
                  decoration: const InputDecoration(labelText: 'Tiêu đề', border: InputBorder.none, hintText: 'Vd: Uống nước'),
                  style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w600),
                ),
                const Divider(height: 1),
                TextField(
                  controller: _note,
                  decoration: const InputDecoration(labelText: 'Ghi chú', border: InputBorder.none),
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),
          CupertinoSlidingSegmentedControl<RType>(
            groupValue: r.type,
            children: const {
              RType.countdown: Padding(padding: EdgeInsets.all(8), child: Text('Đếm ngược')),
              RType.repeat: Padding(padding: EdgeInsets.all(8), child: Text('Lặp lại')),
              RType.once: Padding(padding: EdgeInsets.all(8), child: Text('Ngày giờ')),
            },
            onValueChanged: (v) => setState(() => r.type = v ?? r.type),
          ),
          const SizedBox(height: 12),
          Glass(child: _typeEditor()),
          const SizedBox(height: 16),
          Glass(child: _soundEditor(t)),
          if (widget.reminder != null) ...[
            const SizedBox(height: 16),
            Center(
              child: GlassButton(
              icon: Icons.notifications_active_outlined,
              label: 'Thử chuông',
              onPressed: () async {
                await Native.test(r.id);
                if (!context.mounted) return;
                await showDialog<void>(
                  context: context,
                  builder: (c) => AlertDialog(
                    title: const Text('Đang thử chuông…'),
                    content: const Text('Đây là âm thanh đã lưu của nhắc nhở này (lưu thay đổi trước để thử âm mới).'),
                    actions: [FilledButton(onPressed: () => Navigator.pop(c), child: const Text('Tắt'))],
                  ),
                );
                Native.stopRinging();
              },
              ),
            ),
          ],
        ],
      ),
    );
  }

  Widget _typeEditor() {
    switch (r.type) {
      case RType.countdown:
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Báo sau', style: TextStyle(fontWeight: FontWeight.w600)),
            SizedBox(
              height: 190,
              child: CupertinoTimerPicker(
                mode: CupertinoTimerPickerMode.hms,
                initialTimerDuration: Duration(seconds: r.durationSec),
                onTimerDurationChanged: (d) => r.durationSec = d.inSeconds.clamp(1, 24 * 3600 * 7),
              ),
            ),
            const Text('Bắt đầu đếm khi bấm Lưu. Bật lại công tắc ở danh sách để đếm lại từ đầu.',
                style: TextStyle(fontSize: 12, color: Colors.black54)),
          ],
        );
      case RType.repeat:
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Text('Mỗi', style: TextStyle(fontWeight: FontWeight.w600)),
                const SizedBox(width: 10),
                GlassIconButton(
                  onPressed: r.every > 1 ? () => setState(() => r.every--) : null,
                  icon: Icons.remove,
                ),
                SizedBox(
                  width: 44,
                  child: Text('${r.every}', textAlign: TextAlign.center, style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w700)),
                ),
                GlassIconButton(onPressed: () => setState(() => r.every++), icon: Icons.add),
                const SizedBox(width: 10),
                DropdownButton<RUnit>(
                  value: r.unit,
                  items: [for (final u in RUnit.values) DropdownMenuItem(value: u, child: Text(unitLabel[u]!))],
                  onChanged: (u) => setState(() => r.unit = u ?? r.unit),
                ),
              ],
            ),
            const SizedBox(height: 8),
            ListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('Lần đầu lúc'),
              subtitle: Text(fmtDateTime(DateTime.fromMillisecondsSinceEpoch(r.anchor))),
              trailing: const Icon(Icons.edit_calendar_rounded),
              onTap: () => _pickDateTime(r.anchor, (v) => r.anchor = v),
            ),
            const Text('Các lần sau lặp theo chu kỳ tính từ mốc này (vd: mỗi tháng → cùng ngày, cùng giờ).',
                style: TextStyle(fontSize: 12, color: Colors.black54)),
          ],
        );
      case RType.once:
        return ListTile(
          contentPadding: EdgeInsets.zero,
          title: const Text('Báo vào lúc', style: TextStyle(fontWeight: FontWeight.w600)),
          subtitle: Text(fmtDateTime(DateTime.fromMillisecondsSinceEpoch(r.at)), style: const TextStyle(fontSize: 18)),
          trailing: const Icon(Icons.edit_calendar_rounded),
          onTap: () => _pickDateTime(r.at, (v) => r.at = v),
        );
    }
  }

  Widget _soundEditor(ThemeData t) {
    final hasRec = _recordingPath != null;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text('Âm thanh', style: TextStyle(fontWeight: FontWeight.w600)),
        RadioGroup<bool>(
          groupValue: r.useRecording,
          onChanged: (v) => setState(() => r.useRecording = v ?? false),
          child: Column(
            children: [
              const RadioListTile<bool>(
                contentPadding: EdgeInsets.zero,
                value: false,
                title: Text('Chuông điện thoại (mặc định)'),
              ),
              RadioListTile<bool>(
                contentPadding: EdgeInsets.zero,
                value: true,
                enabled: hasRec || _recording,
                title: const Text('Ghi âm của tôi'),
                subtitle: Text(hasRec ? 'Đã có bản ghi' : 'Bấm nút micro bên dưới để ghi'),
              ),
            ],
          ),
        ),
        Row(
          children: [
            GlassButton(
              tint: _recording ? Colors.redAccent : kAccent,
              onPressed: _toggleRecord,
              icon: _recording ? Icons.stop_rounded : Icons.mic_rounded,
              label: _recording ? 'Dừng ${fmtDuration(Duration(milliseconds: _recMs))}' : (hasRec ? 'Ghi lại' : 'Ghi âm'),
            ),
            const SizedBox(width: 10),
            if (hasRec && !_recording)
              GlassButton(
                onPressed: _togglePlay,
                icon: _playing ? Icons.stop_rounded : Icons.play_arrow_rounded,
                label: _playing ? 'Dừng' : 'Nghe thử',
              ),
          ],
        ),
        const Divider(height: 24),
        SwitchListTile(
          contentPadding: EdgeInsets.zero,
          title: const Text('Vẫn kêu khi máy tắt tiếng'),
          subtitle: const Text('Phát qua kênh báo thức và tự tăng âm lượng báo thức khi đang thấp'),
          value: r.boostVolume,
          onChanged: (v) => setState(() => r.boostVolume = v),
        ),
        SwitchListTile(
          contentPadding: EdgeInsets.zero,
          title: const Text('Rung'),
          value: r.vibrate,
          onChanged: (v) => setState(() => r.vibrate = v),
        ),
        ListTile(
          contentPadding: EdgeInsets.zero,
          title: const Text('Kêu trong'),
          trailing: DropdownButton<int>(
            value: r.ringSeconds,
            underline: const SizedBox(),
            items: const [
              DropdownMenuItem(value: 15, child: Text('15 giây')),
              DropdownMenuItem(value: 30, child: Text('30 giây')),
              DropdownMenuItem(value: 60, child: Text('1 phút')),
              DropdownMenuItem(value: 180, child: Text('3 phút')),
              DropdownMenuItem(value: 300, child: Text('5 phút')),
            ],
            onChanged: (v) => setState(() => r.ringSeconds = v ?? 60),
          ),
        ),
      ],
    );
  }
}

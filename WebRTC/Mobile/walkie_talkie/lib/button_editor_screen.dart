import 'package:flutter/material.dart';

import 'app_icons.dart';
import 'audio.dart';
import 'config_store.dart';
import 'models.dart';

/// Edit a single soundboard button: name, icon, and its recorded sound.
/// Returns true if the user saved changes.
class ButtonEditorScreen extends StatefulWidget {
  final ConfigStore store;
  final AudioEngine engine;
  final SoundButton button;
  const ButtonEditorScreen({
    super.key,
    required this.store,
    required this.engine,
    required this.button,
  });

  @override
  State<ButtonEditorScreen> createState() => _ButtonEditorScreenState();
}

class _ButtonEditorScreenState extends State<ButtonEditorScreen> {
  late final TextEditingController _nameCtrl;
  late int _iconIndex;
  String? _soundFileName;
  bool _recording = false;

  @override
  void initState() {
    super.initState();
    _nameCtrl = TextEditingController(text: widget.button.name);
    _iconIndex = widget.button.iconIndex;
    _soundFileName = widget.button.soundFileName;
  }

  @override
  void dispose() {
    _nameCtrl.dispose();
    super.dispose();
  }

  Future<void> _toggleRecord() async {
    if (_recording) {
      await widget.engine.stopClipRecording();
      setState(() => _recording = false);
      return;
    }
    final fileName = '${widget.button.id}.m4a';
    final file = await widget.store.soundFile(fileName);
    final ok = await widget.engine.startClipRecording(file.path);
    if (!ok) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('Cần quyền micro để ghi âm.')));
      }
      return;
    }
    setState(() {
      _recording = true;
      _soundFileName = fileName;
    });
  }

  Future<void> _preview() async {
    if (_soundFileName == null) return;
    final file = await widget.store.soundFile(_soundFileName!);
    if (await file.exists()) widget.engine.previewClip(file.path);
  }

  Future<void> _deleteSound() async {
    if (_soundFileName != null) {
      final f = await widget.store.soundFile(_soundFileName!);
      if (await f.exists()) {
        try {
          await f.delete();
        } catch (_) {}
      }
    }
    setState(() => _soundFileName = null);
  }

  void _save() {
    widget.button
      ..name = _nameCtrl.text.trim().isEmpty ? 'Nút' : _nameCtrl.text.trim()
      ..iconIndex = _iconIndex
      ..soundFileName = _soundFileName;
    Navigator.pop(context, true);
  }

  @override
  Widget build(BuildContext context) {
    final hasSound = _soundFileName != null;
    return Scaffold(
      appBar: AppBar(
        title: const Text('Cấu hình nút'),
        actions: [
          TextButton(
            onPressed: _save,
            child: const Text('Lưu'),
          ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          TextField(
            controller: _nameCtrl,
            decoration: const InputDecoration(
              labelText: 'Tên nút',
              border: OutlineInputBorder(),
            ),
            textInputAction: TextInputAction.done,
          ),
          const SizedBox(height: 24),
          const Text('Ghi âm cho nút',
              style: TextStyle(fontWeight: FontWeight.w600)),
          const SizedBox(height: 8),
          Row(
            children: [
              FilledButton.icon(
                onPressed: _toggleRecord,
                icon: Icon(_recording ? Icons.stop : Icons.fiber_manual_record),
                label: Text(_recording ? 'Dừng ghi' : 'Ghi âm'),
                style: FilledButton.styleFrom(
                  backgroundColor: _recording
                      ? Theme.of(context).colorScheme.error
                      : null,
                ),
              ),
              const SizedBox(width: 10),
              OutlinedButton.icon(
                onPressed: hasSound && !_recording ? _preview : null,
                icon: const Icon(Icons.play_arrow),
                label: const Text('Nghe thử'),
              ),
              const SizedBox(width: 10),
              if (hasSound && !_recording)
                IconButton(
                  tooltip: 'Xoá ghi âm',
                  onPressed: _deleteSound,
                  icon: const Icon(Icons.delete_outline),
                ),
            ],
          ),
          const SizedBox(height: 6),
          Text(
            hasSound ? 'Đã có ghi âm.' : 'Chưa có ghi âm.',
            style: TextStyle(color: Theme.of(context).hintColor, fontSize: 12.5),
          ),
          const SizedBox(height: 24),
          const Text('Chọn biểu tượng',
              style: TextStyle(fontWeight: FontWeight.w600)),
          const SizedBox(height: 8),
          GridView.builder(
            shrinkWrap: true,
            physics: const NeverScrollableScrollPhysics(),
            gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
              crossAxisCount: 6,
              mainAxisSpacing: 8,
              crossAxisSpacing: 8,
            ),
            itemCount: kButtonIcons.length,
            itemBuilder: (context, i) {
              final selected = i == _iconIndex;
              final cs = Theme.of(context).colorScheme;
              return InkWell(
                borderRadius: BorderRadius.circular(10),
                onTap: () => setState(() => _iconIndex = i),
                child: Container(
                  decoration: BoxDecoration(
                    color: selected ? cs.primaryContainer : cs.surfaceContainerHighest,
                    borderRadius: BorderRadius.circular(10),
                    border: Border.all(
                      color: selected ? cs.primary : Colors.transparent,
                      width: 2,
                    ),
                  ),
                  child: Icon(kButtonIcons[i],
                      color: selected ? cs.onPrimaryContainer : null),
                ),
              );
            },
          ),
        ],
      ),
    );
  }
}

import 'package:flutter/material.dart';

import 'models.dart';
import 'ui.dart';

/// Dialog to edit a collection's name and its 1–3 lines (name + language).
Future<bool> editCollectionInfo(BuildContext context, Collection c, {bool isNew = false}) async {
  final name = TextEditingController(text: c.name);
  final fields = [for (final f in c.fields) Field(f.name, f.lang)];
  final ok = await showDialog<bool>(
    context: context,
    builder: (ctx) => StatefulBuilder(
      builder: (ctx, set) => AlertDialog(
        title: Text(isNew ? 'Bộ từ mới' : 'Sửa bộ từ'),
        content: SizedBox(
          width: 400,
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                TextField(
                  controller: name,
                  autofocus: isNew,
                  decoration: const InputDecoration(labelText: 'Tên bộ từ', border: OutlineInputBorder()),
                ),
                const SizedBox(height: 16),
                const Text('Các dòng của mỗi từ (tối đa 3)', style: TextStyle(fontWeight: FontWeight.w600)),
                const SizedBox(height: 4),
                const Text(
                  'Vd: Hán tự (Trung) · Pinyin (Khác) · Nghĩa (Việt). Dòng có ngôn ngữ sẽ đọc được bằng loa.',
                  style: TextStyle(fontSize: 12, color: Colors.black54),
                ),
                const SizedBox(height: 8),
                for (var i = 0; i < fields.length; i++)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 8),
                    child: Row(
                      children: [
                        Text('${i + 1}.', style: const TextStyle(fontWeight: FontWeight.w700)),
                        const SizedBox(width: 6),
                        Expanded(
                          flex: 5,
                          child: TextFormField(
                            initialValue: fields[i].name,
                            decoration: const InputDecoration(labelText: 'Tên dòng', isDense: true),
                            onChanged: (v) => fields[i].name = v,
                          ),
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          flex: 6,
                          child: DropdownButtonFormField<String>(
                            initialValue: fields[i].lang,
                            isExpanded: true,
                            decoration: const InputDecoration(labelText: 'Ngôn ngữ', isDense: true),
                            items: [
                              for (final l in Lang.all) DropdownMenuItem(value: l.code, child: Text('${l.flag} ${l.label}')),
                            ],
                            onChanged: (v) => fields[i].lang = v ?? '',
                          ),
                        ),
                        IconButton(
                          icon: const Icon(Icons.remove_circle_outline),
                          onPressed: fields.length <= 1 ? null : () => set(() => fields.removeAt(i)),
                        ),
                      ],
                    ),
                  ),
                if (fields.length < 3)
                  TextButton.icon(
                    onPressed: () => set(() => fields.add(Field('Dòng ${fields.length + 1}', ''))),
                    icon: const Icon(Icons.add),
                    label: const Text('Thêm dòng'),
                  ),
              ],
            ),
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Huỷ')),
          FilledButton(
            onPressed: () {
              if (name.text.trim().isEmpty) return;
              Navigator.pop(ctx, true);
            },
            child: const Text('Lưu'),
          ),
        ],
      ),
    ),
  );
  if (ok != true) return false;
  c.name = name.text.trim();
  c.fields = [for (final f in fields) Field(f.name.trim().isEmpty ? 'Dòng' : f.name.trim(), f.lang)];
  return true;
}

enum _Filter { all, todo, learned }

class CollectionPage extends StatefulWidget {
  const CollectionPage({super.key, required this.store, required this.collection, required this.onChanged});
  final VocabStore store;
  final Collection collection;
  final Future<void> Function() onChanged;

  @override
  State<CollectionPage> createState() => _CollectionPageState();
}

class _CollectionPageState extends State<CollectionPage> {
  Collection get c => widget.store.byId(widget.collection.id) ?? widget.collection;
  String _query = '';
  _Filter _filter = _Filter.all;

  Future<void> _save() async {
    await widget.onChanged();
    if (mounted) setState(() {});
  }

  Future<void> _editWord([Word? word]) async {
    final ctrls = [
      for (var i = 0; i < c.fields.length; i++)
        TextEditingController(text: word != null && i < word.values.length ? word.values[i] : ''),
    ];
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(word == null ? 'Thêm từ' : 'Sửa từ'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            for (var i = 0; i < c.fields.length; i++)
              Padding(
                padding: const EdgeInsets.only(bottom: 10),
                child: TextField(
                  controller: ctrls[i],
                  autofocus: i == 0,
                  decoration: InputDecoration(
                    labelText: '${Lang.of(c.fields[i].lang).flag} ${c.fields[i].name}',
                    border: const OutlineInputBorder(),
                  ),
                ),
              ),
          ],
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Huỷ')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Lưu')),
        ],
      ),
    );
    if (ok != true) return;
    final values = ctrls.map((e) => e.text.trim()).toList();
    if (values.every((v) => v.isEmpty)) return;
    if (word == null) {
      c.words.add(Word(id: newId(), values: values));
    } else {
      word.values = values;
    }
    await _save();
  }

  /// Paste many words: one per line, columns separated by "|" or Tab.
  Future<void> _bulkAdd() async {
    final ctrl = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Dán nhiều từ'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Mỗi dòng 1 từ, các cột cách nhau bằng dấu | hoặc Tab, theo thứ tự: '
              '${c.fields.map((f) => f.name).join(' | ')}',
              style: const TextStyle(fontSize: 12, color: Colors.black54),
            ),
            const SizedBox(height: 8),
            TextField(
              controller: ctrl,
              maxLines: 8,
              decoration: InputDecoration(
                hintText: c.fields.map((f) => f.name).join(' | '),
                border: const OutlineInputBorder(),
              ),
            ),
          ],
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Huỷ')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Thêm')),
        ],
      ),
    );
    if (ok != true) return;
    var n = 0;
    for (final line in ctrl.text.split('\n')) {
      if (line.trim().isEmpty) continue;
      final parts = line.split(RegExp(r'\s*[|\t]\s*')).map((e) => e.trim()).toList();
      c.words.add(Word(id: newId(), values: [for (var i = 0; i < c.fields.length; i++) i < parts.length ? parts[i] : '']));
      n++;
    }
    await _save();
    if (mounted) toast(context, 'Đã thêm $n từ');
  }

  @override
  Widget build(BuildContext context) {
    final q = _query.toLowerCase();
    final words = c.words.where((w) {
      if (_filter == _Filter.todo && w.learned) return false;
      if (_filter == _Filter.learned && !w.learned) return false;
      return q.isEmpty || w.values.any((v) => v.toLowerCase().contains(q));
    }).toList();
    return Scaffold(
      appBar: AppBar(
        title: Text(c.name, style: const TextStyle(fontWeight: FontWeight.w700)),
        actions: [
          IconButton(
            tooltip: 'Sửa tên / các dòng',
            icon: const Icon(Icons.tune_rounded),
            onPressed: () async {
              if (await editCollectionInfo(context, c)) await _save();
            },
          ),
          PopupMenuButton<String>(
            onSelected: (v) async {
              if (v == 'bulk') _bulkAdd();
              if (v == 'reset' && await confirm(context, 'Học lại từ đầu?', 'Bỏ đánh dấu "đã thuộc" của mọi từ trong bộ này?', ok: 'Đặt lại')) {
                for (final w in c.words) {
                  w.learned = false;
                }
                await _save();
              }
            },
            itemBuilder: (_) => const [
              PopupMenuItem(value: 'bulk', child: Text('Dán nhiều từ')),
              PopupMenuItem(value: 'reset', child: Text('Đặt lại "đã thuộc"')),
            ],
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton(onPressed: () => _editWord(), child: const Icon(Icons.add)),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 4, 16, 8),
            child: TextField(
              decoration: InputDecoration(
                prefixIcon: const Icon(Icons.search),
                hintText: 'Tìm từ…',
                filled: true,
                fillColor: Colors.white.withValues(alpha: 0.7),
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(14), borderSide: BorderSide.none),
                isDense: true,
              ),
              onChanged: (v) => setState(() => _query = v),
            ),
          ),
          SegmentedButton<_Filter>(
            segments: [
              ButtonSegment(value: _Filter.all, label: Text('Tất cả (${c.words.length})')),
              ButtonSegment(value: _Filter.todo, label: Text('Chưa thuộc (${c.words.length - c.learnedCount})')),
              ButtonSegment(value: _Filter.learned, label: Text('Đã thuộc (${c.learnedCount})')),
            ],
            selected: {_filter},
            showSelectedIcon: false,
            onSelectionChanged: (s) => setState(() => _filter = s.first),
          ),
          const SizedBox(height: 8),
          Expanded(
            child: words.isEmpty
                ? const Center(child: Text('Không có từ nào'))
                : ListView.separated(
                    padding: const EdgeInsets.fromLTRB(16, 4, 16, 100),
                    itemCount: words.length,
                    separatorBuilder: (_, _) => const SizedBox(height: 10),
                    itemBuilder: (context, i) => _wordTile(words[i]),
                  ),
          ),
        ],
      ),
    );
  }

  Widget _wordTile(Word w) {
    return Dismissible(
      key: ValueKey(w.id),
      direction: DismissDirection.endToStart,
      background: Container(
        alignment: Alignment.centerRight,
        padding: const EdgeInsets.only(right: 24),
        decoration: BoxDecoration(color: Colors.redAccent, borderRadius: BorderRadius.circular(22)),
        child: const Icon(Icons.delete_outline, color: Colors.white),
      ),
      confirmDismiss: (_) => confirm(context, 'Xoá từ?', 'Xoá "${w.values.firstOrNull ?? ''}"?'),
      onDismissed: (_) {
        c.words.removeWhere((e) => e.id == w.id);
        _save();
      },
      child: Glass(
        padding: const EdgeInsets.fromLTRB(16, 10, 6, 10),
        onTap: () => _editWord(w),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  for (var i = 0; i < c.fields.length; i++)
                    Row(
                      children: [
                        Expanded(
                          child: Text(
                            i < w.values.length ? w.values[i] : '',
                            style: i == 0
                                ? TextStyle(
                                    fontSize: 20,
                                    fontWeight: FontWeight.w700,
                                    color: w.learned ? Colors.black38 : Colors.black87,
                                    decoration: w.learned ? TextDecoration.lineThrough : null,
                                  )
                                : const TextStyle(fontSize: 15, color: Colors.black54),
                          ),
                        ),
                        if (i < w.values.length && w.values[i].isNotEmpty)
                          SpeakButton(text: w.values[i], lang: c.fields[i].lang, size: i == 0 ? 22 : 18),
                      ],
                    ),
                ],
              ),
            ),
            IconButton(
              tooltip: w.learned ? 'Bỏ đánh dấu đã thuộc' : 'Đánh dấu đã thuộc',
              icon: Icon(
                w.learned ? Icons.check_circle_rounded : Icons.check_circle_outline_rounded,
                color: w.learned ? Colors.green : Colors.black38,
              ),
              onPressed: () {
                w.learned = !w.learned;
                _save();
              },
            ),
          ],
        ),
      ),
    );
  }
}

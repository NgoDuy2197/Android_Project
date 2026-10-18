import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';

import 'models.dart';
import 'native.dart';
import 'preview.dart';
import 'ui.dart';

/// Widget sizes to preview (launcher cells → dp).
const _sizes = <(String, Size)>[
  ('2×2', Size(170, 170)),
  ('4×2', Size(330, 160)),
  ('2×4', Size(160, 330)),
  ('4×4', Size(330, 330)),
];

class EditorPage extends StatefulWidget {
  const EditorPage({super.key, required this.frame, required this.isNew});
  final Frame frame;
  final bool isNew;

  @override
  State<EditorPage> createState() => _EditorPageState();
}

class _EditorPageState extends State<EditorPage> {
  Frame get f => widget.frame;
  late final _name = TextEditingController(text: f.name);
  int _rev = 0;
  int _size = 0;
  int _offset = 0;
  int? _count; // photos in the source

  @override
  void initState() {
    super.initState();
    _countPhotos();
  }

  void _changed(VoidCallback edit) {
    setState(() {
      edit();
      _rev++;
    });
  }

  Future<void> _countPhotos({bool rescan = false}) async {
    final n = await Native.photoCount(f, rescan: rescan);
    if (mounted) setState(() => _count = n);
  }

  Future<void> _setSource(String type) async {
    if (type == 'samples') {
      _changed(() => f.source = {'type': 'samples'});
    } else if (type == 'folder') {
      final r = await Native.pickFolder();
      if (r == null) return;
      _changed(() => f.source = {'type': 'folder', 'uri': r['uri'], 'name': r['name'], 'recursive': true});
    } else {
      final uris = await Native.pickPhotos();
      if (uris.isEmpty) return;
      _changed(() => f.source = {'type': 'photos', 'uris': uris});
    }
    setState(() => _count = null);
    _countPhotos(rescan: true);
  }

  @override
  Widget build(BuildContext context) {
    final src = f.source;
    final size = _sizes[_size].$2;
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.isNew ? 'Khung mới' : 'Sửa khung', style: const TextStyle(fontWeight: FontWeight.w700)),
        actions: [
          TextButton(
            onPressed: () {
              f.name = _name.text.trim().isEmpty ? 'Khung ảnh' : _name.text.trim();
              Navigator.pop(context, f);
            },
            child: const Text('Lưu', style: TextStyle(fontSize: 17, fontWeight: FontWeight.w700)),
          ),
        ],
      ),
      body: Column(
        children: [
          // ---- live preview (sticky)
          SizedBox(
            height: 250,
            child: Center(
              child: FittedBox(
                fit: BoxFit.contain,
                child: Padding(
                  padding: const EdgeInsets.all(8),
                  child: FramePreview(frame: f, sizeDp: size, revision: _rev, offset: _offset),
                ),
              ),
            ),
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: Row(
              children: [
                Expanded(
                  child: CupertinoSlidingSegmentedControl<int>(
                    groupValue: _size,
                    children: {for (var i = 0; i < _sizes.length; i++) i: Text(_sizes[i].$1)},
                    onValueChanged: (v) => setState(() => _size = v ?? 0),
                  ),
                ),
                IconButton(
                  tooltip: 'Ảnh khác',
                  onPressed: () => setState(() => _offset++),
                  icon: const Icon(Icons.shuffle_rounded, color: kAccent),
                ),
              ],
            ),
          ),
          const SizedBox(height: 6),
          Expanded(
            child: ListView(
              padding: const EdgeInsets.fromLTRB(16, 4, 16, 40),
              children: [
                _section('Tên', [
                  TextField(controller: _name, decoration: const InputDecoration(hintText: 'Tên khung', border: InputBorder.none)),
                ]),
                _section('Nguồn ảnh', [
                  CupertinoSlidingSegmentedControl<String>(
                    groupValue: src['type'] as String? ?? 'samples',
                    children: const {
                      'samples': Text('Ảnh mẫu'),
                      'folder': Text('Thư mục'),
                      'photos': Text('Chọn ảnh'),
                    },
                    onValueChanged: (v) => _setSource(v ?? 'samples'),
                  ),
                  const SizedBox(height: 8),
                  Row(
                    children: [
                      Expanded(child: Text('${f.describeSource()} · ${_count == null ? 'đang đếm…' : '$_count ảnh'}')),
                      if (src['type'] == 'folder')
                        TextButton(onPressed: () => _countPhotos(rescan: true), child: const Text('Quét lại')),
                      if (src['type'] != 'samples')
                        TextButton(onPressed: () => _setSource(src['type'] as String), child: const Text('Đổi')),
                    ],
                  ),
                  if (src['type'] == 'folder')
                    SwitchListTile(
                      contentPadding: EdgeInsets.zero,
                      title: const Text('Gồm cả thư mục con'),
                      value: src['recursive'] as bool? ?? true,
                      onChanged: (v) {
                        _changed(() => f.source = {...src, 'recursive': v});
                        _countPhotos(rescan: true);
                      },
                    ),
                ]),
                _section('Ảnh trong khung', [
                  _label('Số ảnh (tự xếp bố cục ít cắt ảnh nhất)'),
                  CupertinoSlidingSegmentedControl<int>(
                    groupValue: f.get<int>('count', 1),
                    children: const {1: Text('1'), 2: Text('2'), 3: Text('3'), 4: Text('4')},
                    onValueChanged: (v) => _changed(() => f.set('count', v ?? 1)),
                  ),
                  _slider('Khoảng cách', 'gap', 0, 12),
                  _slider('Bo góc ảnh', 'photoRadius', 0, 24),
                  _label('Thứ tự đổi ảnh'),
                  CupertinoSlidingSegmentedControl<String>(
                    groupValue: f.get<String>('order', 'sequential'),
                    children: const {'sequential': Text('Tuần tự'), 'random': Text('Ngẫu nhiên')},
                    onValueChanged: (v) => _changed(() => f.set('order', v)),
                  ),
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    title: const Text('Tự đổi ảnh'),
                    trailing: DropdownButton<int>(
                      value: f.get<int>('interval', 60),
                      underline: const SizedBox(),
                      items: const [
                        DropdownMenuItem(value: 0, child: Text('Tắt')),
                        DropdownMenuItem(value: 15, child: Text('15 phút')),
                        DropdownMenuItem(value: 30, child: Text('30 phút')),
                        DropdownMenuItem(value: 60, child: Text('1 giờ')),
                        DropdownMenuItem(value: 180, child: Text('3 giờ')),
                        DropdownMenuItem(value: 720, child: Text('12 giờ')),
                        DropdownMenuItem(value: 1440, child: Text('Mỗi ngày')),
                      ],
                      onChanged: (v) => _changed(() => f.set('interval', v ?? 60)),
                    ),
                  ),
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    title: const Text('Chạm vào ảnh trên widget'),
                    trailing: DropdownButton<String>(
                      value: f.get<String>('tap', 'open') == 'app' ? 'app' : 'open',
                      underline: const SizedBox(),
                      items: const [
                        DropdownMenuItem(value: 'open', child: Text('Mở ảnh')),
                        DropdownMenuItem(value: 'app', child: Text('Mở app')),
                      ],
                      onChanged: (v) => _changed(() => f.set('tap', v)),
                    ),
                  ),
                  SwitchListTile(
                    contentPadding: EdgeInsets.zero,
                    title: const Text('Nút ▸ mờ ở góc để đổi ảnh tiếp'),
                    value: f.get<bool>('nextButton', true),
                    onChanged: (v) => _changed(() => f.set('nextButton', v)),
                  ),
                ]),
                _section('Hình khung', [
                  Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: [
                      for (final (id, label, glyph) in shapes)
                        ChoiceChip(
                          avatar: Text(glyph, style: const TextStyle(fontSize: 16)),
                          label: Text(label),
                          selected: f.get<String>('shape', 'rounded') == id,
                          onSelected: (_) => _changed(() => f.set('shape', id)),
                        ),
                    ],
                  ),
                  if (const ['rounded', 'rect', 'polaroid'].contains(f.get<String>('shape', 'rounded')))
                    _slider('Bo góc khung', 'corner', 0, 60),
                ]),
                _section('Viền', [
                  _slider('Độ dày', 'border', 0, 16),
                  ColorRow(value: f.get<int>('borderColor', 0xFFFFFFFF), onChanged: (c) => _changed(() => f.set('borderColor', c))),
                ]),
                _section('Nền khung', [
                  ColorRow(value: f.get<int>('matColor', 0xFFFFFFFF), onChanged: (c) => _changed(() => f.set('matColor', c))),
                  _slider('Lề trong', 'padding', 0, 20),
                  _slider('Độ đậm khung', 'opacity', 0, 1, percent: true),
                ]),
                _section('Hiệu ứng', [
                  SwitchListTile(
                    contentPadding: EdgeInsets.zero,
                    title: const Text('Đổ bóng'),
                    value: f.get<bool>('shadow', true),
                    onChanged: (v) => _changed(() => f.set('shadow', v)),
                  ),
                  if (f.get<bool>('shadow', true)) _slider('Độ lớn bóng', 'shadowSize', 2, 22),
                  SwitchListTile(
                    contentPadding: EdgeInsets.zero,
                    title: const Text('Ánh kính (bóng loáng kiểu iOS)'),
                    value: f.get<bool>('gloss', false),
                    onChanged: (v) => _changed(() => f.set('gloss', v)),
                  ),
                  SwitchListTile(
                    contentPadding: EdgeInsets.zero,
                    title: const Text('Hiện ngày chụp'),
                    value: f.get<bool>('showDate', false),
                    onChanged: (v) => _changed(() => f.set('showDate', v)),
                  ),
                  _label('Bộ lọc màu'),
                  Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: [
                      for (final (id, label) in filters)
                        ChoiceChip(
                          label: Text(label),
                          selected: f.get<String>('filter', 'none') == id,
                          onSelected: (_) => _changed(() => f.set('filter', id)),
                        ),
                    ],
                  ),
                ]),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _section(String title, List<Widget> children) => Padding(
    padding: const EdgeInsets.only(bottom: 14),
    child: Glass(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title, style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 15)),
          const SizedBox(height: 8),
          ...children,
        ],
      ),
    ),
  );

  Widget _label(String s) => Padding(
    padding: const EdgeInsets.only(top: 10, bottom: 6),
    child: Text(s, style: const TextStyle(color: Colors.black54)),
  );

  Widget _slider(String label, String key, double min, double max, {bool percent = false}) {
    final v = f.dbl(key, min).clamp(min, max);
    return Row(
      children: [
        SizedBox(width: 104, child: Text(label)),
        Expanded(
          child: Slider(
            value: v,
            min: min,
            max: max,
            onChanged: (x) => _changed(() => f.set(key, x)),
          ),
        ),
        SizedBox(
          width: 44,
          child: Text(percent ? '${(v * 100).round()}%' : v.toStringAsFixed(0), textAlign: TextAlign.right),
        ),
      ],
    );
  }
}

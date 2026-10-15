import 'dart:io';
import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:image_picker/image_picker.dart';

import 'image_tools.dart';
import 'models.dart';
import 'widgets.dart';

/// Picks a photo (gallery or camera) and opens [ImportPage] to add it to the
/// cart. Returns the new product, or null if cancelled.
Future<Product?> importProduct(
  BuildContext context,
  CartStore store,
  BodyPart part,
) async {
  final source = await showModalBottomSheet<ImageSource>(
    context: context,
    backgroundColor: Colors.transparent,
    builder: (c) => SafeArea(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Glass(
          opacity: 0.22,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              ListTile(
                leading: const Icon(Icons.photo_library_outlined),
                title: const Text('Chọn ảnh từ thư viện'),
                onTap: () => Navigator.pop(c, ImageSource.gallery),
              ),
              ListTile(
                leading: const Icon(Icons.photo_camera_outlined),
                title: const Text('Chụp ảnh sản phẩm'),
                onTap: () => Navigator.pop(c, ImageSource.camera),
              ),
            ],
          ),
        ),
      ),
    ),
  );
  if (source == null) return null;
  final XFile? file;
  try {
    file = await ImagePicker().pickImage(source: source, maxWidth: 2000, maxHeight: 2000);
  } catch (e) {
    if (context.mounted) toast(context, 'Không mở được ảnh: $e');
    return null;
  }
  if (file == null || !context.mounted) return null;
  final bytes = await file.readAsBytes();
  if (!context.mounted) return null;
  return Navigator.of(context).push<Product>(
    MaterialPageRoute(builder: (_) => ImportPage(store: store, part: part, bytes: bytes)),
  );
}

class ImportPage extends StatefulWidget {
  const ImportPage({super.key, required this.store, required this.part, required this.bytes});
  final CartStore store;
  final BodyPart part;
  final Uint8List bytes;

  @override
  State<ImportPage> createState() => _ImportPageState();
}


enum _View { result, original }

class _ImportPageState extends State<ImportPage> {
  late BodyPart _part = widget.part;
  late final _name = TextEditingController(
    text: '${widget.part.itemLabel.split(' /').first} ${widget.store.itemsFor(widget.part).length + 1}',
  );
  EditableImage? _src;
  bool _autoBg = true;
  double _tolerance = 42;
  final List<int> _keys = [];
  bool _keyBorderOnly = false;
  _View _view = _View.result;
  Uint8List? _png;
  bool _busy = true;
  bool _failed = false;
  int _job = 0;

  @override
  void initState() {
    super.initState();
    _decode();
  }

  Future<void> _decode() async {
    final src = await compute(decodeForEdit, widget.bytes);
    if (!mounted) return;
    if (src == null) {
      setState(() {
        _failed = true;
        _busy = false;
      });
      return;
    }
    _src = src;
    await _process();
  }

  Future<void> _process() async {
    final src = _src;
    if (src == null) return;
    final job = ++_job;
    setState(() => _busy = true);
    final png = await compute(
      prepareProductImage,
      PrepareArgs(
        src,
        autoRemove: _autoBg,
        tolerance: _tolerance.round(),
        keyColors: List.of(_keys),
        keyBorderOnly: _keyBorderOnly,
      ),
    );
    if (!mounted || job != _job) return;
    setState(() {
      _png = png;
      _busy = false;
    });
  }

  /// Tap on the original image: pick the colour under the finger.
  void _pickAt(Offset local, Size box) {
    final src = _src!;
    final scale = math.min(box.width / src.width, box.height / src.height);
    final dw = src.width * scale, dh = src.height * scale;
    final x = ((local.dx - (box.width - dw) / 2) / scale).floor();
    final y = ((local.dy - (box.height - dh) / 2) / scale).floor();
    if (x < 0 || y < 0 || x >= src.width || y >= src.height) return;
    final c = src.sampleColor(x, y);
    HapticFeedback.selectionClick();
    setState(() {
      if (!_keys.contains(c)) _keys.add(c);
      _view = _View.result;
    });
    _process();
  }

  Future<void> _save() async {
    final png = _png;
    if (png == null) return;
    final id = DateTime.now().microsecondsSinceEpoch.toString();
    final file = File('${widget.store.productDir.path}/$id.png');
    await file.writeAsBytes(png);
    final p = Product(
      id: id,
      name: _name.text.trim().isEmpty ? _part.itemLabel : _name.text.trim(),
      part: _part,
      path: file.path,
    );
    widget.store.add(p);
    if (mounted) Navigator.pop(context, p);
  }

  @override
  void dispose() {
    _name.dispose();
    super.dispose();
  }

  Widget _preview() {
    if (_failed) {
      return const Center(child: Text('Không đọc được ảnh', style: TextStyle(color: Colors.black54)));
    }
    final src = _src;
    if (src == null) return const Center(child: CircularProgressIndicator());
    if (_view == _View.original) {
      return Padding(
        padding: const EdgeInsets.all(12),
        child: LayoutBuilder(
          builder: (context, c) => GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTapUp: (d) => _pickAt(d.localPosition, c.biggest),
            child: Image.memory(src.png, fit: BoxFit.contain, width: c.maxWidth, height: c.maxHeight, gaplessPlayback: true),
          ),
        ),
      );
    }
    return Stack(
      alignment: Alignment.center,
      children: [
        if (_png != null)
          Padding(
            padding: const EdgeInsets.all(12),
            child: Image.memory(_png!, gaplessPlayback: true),
          ),
        if (_busy) const CircularProgressIndicator(),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    final picking = _view == _View.original;
    return Scaffold(
      appBar: AppBar(title: const Text('Thêm vào giỏ hàng')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          AspectRatio(
            aspectRatio: 1,
            child: Checker(
              radius: 24,
              child: Stack(
                fit: StackFit.expand,
                children: [
                  _preview(),
                  if (picking)
                    const Positioned(
                      left: 12,
                      right: 12,
                      top: 12,
                      child: IgnorePointer(
                        child: Glass(
                          opacity: 0.55,
                          padding: EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                          child: Text(
                            'Chạm vào màu nền cần xoá',
                            textAlign: TextAlign.center,
                            style: TextStyle(color: Colors.black87, fontWeight: FontWeight.w600),
                          ),
                        ),
                      ),
                    ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 12),
          SegmentedButton<_View>(
            segments: const [
              ButtonSegment(value: _View.result, icon: Icon(Icons.auto_awesome), label: Text('Kết quả')),
              ButtonSegment(value: _View.original, icon: Icon(Icons.colorize), label: Text('Chọn màu nền')),
            ],
            selected: {_view},
            onSelectionChanged: _src == null ? null : (s) => setState(() => _view = s.first),
          ),
          const SizedBox(height: 12),
          const Text('Màu nền sẽ xoá', style: TextStyle(fontWeight: FontWeight.w600)),
          const SizedBox(height: 8),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              for (final k in _keys)
                InputChip(
                  avatar: CircleAvatar(backgroundColor: Color(0xFF000000 | k)),
                  label: Text('#${k.toRadixString(16).padLeft(6, '0').toUpperCase()}'),
                  onDeleted: () {
                    setState(() => _keys.remove(k));
                    _process();
                  },
                ),
              ActionChip(
                avatar: const Icon(Icons.colorize, size: 18),
                label: Text(_keys.isEmpty ? 'Chọn màu trên ảnh' : 'Thêm màu'),
                onPressed: _src == null ? null : () => setState(() => _view = _View.original),
              ),
            ],
          ),
          if (_keys.isNotEmpty)
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('Chỉ xoá vùng nối với viền ảnh'),
              subtitle: const Text('Bật nếu sản phẩm có chi tiết cùng màu với nền'),
              value: _keyBorderOnly,
              onChanged: (v) {
                setState(() => _keyBorderOnly = v);
                _process();
              },
            ),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text('Tự động tách nền'),
            subtitle: const Text('Đoán màu nền từ viền ảnh (nền trắng / trơn)'),
            value: _autoBg,
            onChanged: (v) {
              setState(() => _autoBg = v);
              _process();
            },
          ),
          if (_autoBg || _keys.isNotEmpty)
            Row(
              children: [
                const Text('Độ mạnh'),
                Expanded(
                  child: Slider(
                    min: 10,
                    max: 110,
                    value: _tolerance,
                    onChanged: (v) => setState(() => _tolerance = v),
                    onChangeEnd: (_) => _process(),
                  ),
                ),
                SizedBox(width: 32, child: Text('${_tolerance.round()}', textAlign: TextAlign.right)),
              ],
            ),
          const SizedBox(height: 12),
          TextField(
            controller: _name,
            decoration: const InputDecoration(labelText: 'Tên sản phẩm', border: OutlineInputBorder()),
          ),
          const SizedBox(height: 16),
          const Text('Mặc ở bộ phận', style: TextStyle(fontWeight: FontWeight.w600)),
          const SizedBox(height: 8),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              for (final p in BodyPart.values)
                ChoiceChip(
                  avatar: Icon(p.icon, size: 18),
                  label: Text('${p.label} · ${p.itemLabel}'),
                  selected: _part == p,
                  onSelected: (_) => setState(() => _part = p),
                ),
            ],
          ),
          const SizedBox(height: 20),
          FilledButton.icon(
            style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(52)),
            onPressed: _png == null || _busy ? null : _save,
            icon: const Icon(Icons.add_shopping_cart),
            label: const Text('Thêm vào giỏ & mặc thử'),
          ),
        ],
      ),
    );
  }
}
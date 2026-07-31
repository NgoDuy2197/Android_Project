import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../media_item.dart';
import '../native_bridge.dart';

/// "Thành quả" tab: a grid of everything captured, newest first. Photos and
/// videos stored in the default app folder render/play in-app; items in a
/// custom SAF folder open through the system viewer.
class GalleryPage extends StatefulWidget {
  const GalleryPage({super.key});

  @override
  State<GalleryPage> createState() => _GalleryPageState();
}

class _GalleryPageState extends State<GalleryPage> {
  final _bridge = NativeBridge.instance;
  StreamSubscription<CaptureEvent>? _sub;

  List<MediaItem> _items = [];
  bool _loading = true;

  // Which captures to show: all, manual only, or motion-detected only.
  String _filter = 'all'; // 'all' | 'manual' | 'motion'

  List<MediaItem> get _visible => switch (_filter) {
        'manual' => _items.where((e) => !e.isMotion).toList(),
        'motion' => _items.where((e) => e.isMotion).toList(),
        _ => _items,
      };

  // Multi-select state. Items are tracked by their unique uri.
  bool _selecting = false;
  final Set<String> _selected = {};

  @override
  void initState() {
    super.initState();
    _refresh();
    // A capture from anywhere (widget, motion, in-app) refreshes the grid.
    _sub = _bridge.events.listen((e) {
      if (e.type == CaptureEventType.captured) _refresh();
    });
  }

  Future<void> _refresh() async {
    final items = await _bridge.listMedia();
    if (!mounted) return;
    setState(() {
      _items = items;
      _loading = false;
    });
  }

  void _toggleSelect(MediaItem item) {
    setState(() {
      if (_selected.contains(item.uri)) {
        _selected.remove(item.uri);
        if (_selected.isEmpty) _selecting = false;
      } else {
        _selected.add(item.uri);
      }
    });
  }

  void _enterSelection(MediaItem item) {
    setState(() {
      _selecting = true;
      _selected.add(item.uri);
    });
  }

  void _exitSelection() {
    setState(() {
      _selecting = false;
      _selected.clear();
    });
  }

  void _toggleSelectAll() {
    final visible = _visible;
    setState(() {
      if (_selected.length == visible.length) {
        _selected.clear();
        _selecting = false;
      } else {
        _selecting = true;
        _selected
          ..clear()
          ..addAll(visible.map((e) => e.uri));
      }
    });
  }

  Future<void> _deleteSelected() async {
    final targets = _items.where((i) => _selected.contains(i.uri)).toList();
    if (targets.isEmpty) return;
    final ok = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('Xoá mục đã chọn?'),
        content: Text('Sẽ xoá ${targets.length} mục. Không thể hoàn tác.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Huỷ'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            style: TextButton.styleFrom(foregroundColor: const Color(0xFFE5484D)),
            child: const Text('Xoá'),
          ),
        ],
      ),
    );
    if (ok != true) return;
    for (final item in targets) {
      await _bridge.deleteMedia(item);
    }
    _exitSelection();
    await _refresh();
  }

  Future<void> _open(MediaItem item) async {
    if (_selecting) {
      _toggleSelect(item);
      return;
    }
    // Videos open through the system chooser (ACTION_VIEW), which lets the user
    // pick a player and tick "Always". Photos view in-app when local.
    if (item.isVideo || !item.hasLocalFile) {
      await _bridge.openMedia(item);
      return;
    }
    await Navigator.of(context).push(
      MaterialPageRoute(builder: (_) => _PhotoViewer(item: item)),
    );
  }

  @override
  void dispose() {
    _sub?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final allSelected = _visible.isNotEmpty && _selected.length == _visible.length;
    return Scaffold(
      appBar: _selecting
          ? AppBar(
              leading: IconButton(
                icon: const Icon(Icons.close),
                onPressed: _exitSelection,
              ),
              title: Text('Đã chọn ${_selected.length}'),
              actions: [
                IconButton(
                  tooltip: allSelected ? 'Bỏ chọn tất cả' : 'Chọn tất cả',
                  icon: Icon(allSelected ? Icons.deselect : Icons.select_all),
                  onPressed: _toggleSelectAll,
                ),
                IconButton(
                  tooltip: 'Xoá',
                  icon: const Icon(Icons.delete),
                  color: const Color(0xFFE5484D),
                  onPressed: _selected.isEmpty ? null : _deleteSelected,
                ),
              ],
            )
          : AppBar(
              title: const Text('Thư viện'),
              centerTitle: true,
              actions: [
                IconButton(onPressed: _refresh, icon: const Icon(Icons.refresh)),
              ],
            ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : Column(
              children: [
                _filterBar(),
                Expanded(
                  child: _visible.isEmpty
                      ? _empty()
                      : RefreshIndicator(
                          onRefresh: _refresh,
                          child: GridView.builder(
                            padding: const EdgeInsets.all(8),
                            gridDelegate:
                                const SliverGridDelegateWithFixedCrossAxisCount(
                              crossAxisCount: 3,
                              crossAxisSpacing: 8,
                              mainAxisSpacing: 8,
                            ),
                            itemCount: _visible.length,
                            itemBuilder: (_, i) => _tile(_visible[i]),
                          ),
                        ),
                ),
              ],
            ),
    );
  }

  Widget _filterBar() {
    final motionCount = _items.where((e) => e.isMotion).length;
    Widget chip(String value, String label) {
      final on = _filter == value;
      return Padding(
        padding: const EdgeInsets.only(right: 8),
        child: ChoiceChip(
          label: Text(label),
          selected: on,
          onSelected: (_) => setState(() => _filter = value),
        ),
      );
    }

    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 0),
      child: Row(
        children: [
          chip('all', 'Tất cả (${_items.length})'),
          chip('manual', 'Chụp tay'),
          chip('motion', 'Chuyển động ($motionCount)'),
        ],
      ),
    );
  }

  Widget _empty() {
    return ListView(
      children: const [
        SizedBox(height: 120),
        Icon(Icons.photo_library_outlined, size: 64, color: Colors.white24),
        SizedBox(height: 12),
        Text(
          'Chưa có ảnh hoặc video nào.\nChụp/quay từ tab đầu tiên hoặc từ widget.',
          textAlign: TextAlign.center,
          style: TextStyle(color: Colors.white54),
        ),
      ],
    );
  }

  Widget _tile(MediaItem item) {
    final selected = _selected.contains(item.uri);
    return GestureDetector(
      onTap: () => _open(item),
      onLongPress: () =>
          _selecting ? _toggleSelect(item) : _enterSelection(item),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(12),
        child: Stack(
          fit: StackFit.expand,
          children: [
            if (!item.isVideo && item.hasLocalFile)
              Image.file(File(item.path!), fit: BoxFit.cover,
                  errorBuilder: (_, _, _) => _placeholder(item))
            else
              _placeholder(item),
            if (item.isVideo)
              const Center(
                child: Icon(Icons.play_circle_fill, size: 36, color: Colors.white70),
              ),
            if (_selecting)
              Container(
                color: selected ? Colors.black38 : Colors.transparent,
                alignment: Alignment.topRight,
                padding: const EdgeInsets.all(4),
                child: Icon(
                  selected ? Icons.check_circle : Icons.radio_button_unchecked,
                  color: selected
                      ? Theme.of(context).colorScheme.primary
                      : Colors.white70,
                  size: 22,
                ),
              ),
            Positioned(
              left: 0,
              right: 0,
              bottom: 0,
              child: Container(
                color: Colors.black54,
                padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 2),
                child: Text(
                  DateFormat('dd/MM HH:mm').format(item.date),
                  style: const TextStyle(color: Colors.white, fontSize: 10),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _placeholder(MediaItem item) {
    return Container(
      color: const Color(0xFF1B1F27),
      child: Icon(
        item.isVideo ? Icons.movie_outlined : Icons.image_outlined,
        color: Colors.white38,
        size: 32,
      ),
    );
  }
}

/// Full-screen photo view.
class _PhotoViewer extends StatelessWidget {
  const _PhotoViewer({required this.item});

  final MediaItem item;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(item.name)),
      backgroundColor: Colors.black,
      body: Center(
        child: InteractiveViewer(
          child: Image.file(File(item.path!)),
        ),
      ),
    );
  }
}

import 'package:flutter/material.dart';

import 'native_bridge.dart';

/// Full-screen picker to choose which installed apps are forwarded.
class AppPickerPage extends StatefulWidget {
  const AppPickerPage({
    super.key,
    required this.initialSelected,
    required this.filterModeIndex,
  });

  final Set<String> initialSelected;
  final int filterModeIndex;

  @override
  State<AppPickerPage> createState() => _AppPickerPageState();
}

class _AppPickerPageState extends State<AppPickerPage> {
  final _searchCtrl = TextEditingController();
  late Set<String> _selected;
  List<InstalledApp> _apps = [];
  bool _loading = true;
  bool _hideSystem = true;
  String _query = '';

  @override
  void initState() {
    super.initState();
    _selected = {...widget.initialSelected};
    _load();
  }

  Future<void> _load() async {
    setState(() => _loading = true);
    final apps = await NativeBridge.listInstalledApps(includeIcons: true);
    if (!mounted) return;
    setState(() {
      _apps = apps;
      _loading = false;
    });
  }

  List<InstalledApp> get _filtered {
    final q = _query.trim().toLowerCase();
    return _apps.where((a) {
      if (_hideSystem && a.isSystem && !_selected.contains(a.packageName)) {
        return false;
      }
      if (q.isEmpty) return true;
      return a.label.toLowerCase().contains(q) ||
          a.packageName.toLowerCase().contains(q);
    }).toList();
  }

  void _toggle(String pkg) {
    setState(() {
      if (_selected.contains(pkg)) {
        _selected.remove(pkg);
      } else {
        _selected.add(pkg);
      }
    });
  }

  void _selectVisible() {
    setState(() {
      for (final a in _filtered) {
        _selected.add(a.packageName);
      }
    });
  }

  void _clearVisible() {
    setState(() {
      for (final a in _filtered) {
        _selected.remove(a.packageName);
      }
    });
  }

  @override
  void dispose() {
    _searchCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final filtered = _filtered;
    return Scaffold(
      appBar: AppBar(
        title: Text('Chọn app (${_selected.length})'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, _selected),
            child: const Text('Xong'),
          ),
        ],
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 8),
            child: TextField(
              controller: _searchCtrl,
              decoration: InputDecoration(
                prefixIcon: const Icon(Icons.search),
                hintText: 'Tìm theo tên hoặc package…',
                border: const OutlineInputBorder(),
                isDense: true,
                suffixIcon: _query.isEmpty
                    ? null
                    : IconButton(
                        icon: const Icon(Icons.clear),
                        onPressed: () {
                          _searchCtrl.clear();
                          setState(() => _query = '');
                        },
                      ),
              ),
              onChanged: (v) => setState(() => _query = v),
            ),
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 8),
            child: Row(
              children: [
                FilterChip(
                  label: const Text('Ẩn hệ thống'),
                  selected: _hideSystem,
                  onSelected: (v) => setState(() => _hideSystem = v),
                ),
                const SizedBox(width: 8),
                TextButton(onPressed: _selectVisible, child: const Text('Chọn hết')),
                TextButton(onPressed: _clearVisible, child: const Text('Bỏ hết')),
                const Spacer(),
                IconButton(
                  tooltip: 'Tải lại',
                  onPressed: _loading ? null : _load,
                  icon: const Icon(Icons.refresh),
                ),
              ],
            ),
          ),
          if (widget.filterModeIndex == 0)
            const Padding(
              padding: EdgeInsets.symmetric(horizontal: 16, vertical: 4),
              child: Text(
                'Đang ở chế độ "Tất cả app". Hãy đổi sang Chỉ cho phép / Loại trừ ở màn hình chính để danh sách này có hiệu lực.',
                style: TextStyle(color: Colors.white54, fontSize: 12.5),
              ),
            ),
          const Divider(height: 1),
          Expanded(
            child: _loading
                ? const Center(child: CircularProgressIndicator())
                : filtered.isEmpty
                    ? const Center(
                        child: Text('Không tìm thấy app nào',
                            style: TextStyle(color: Colors.white54)),
                      )
                    : ListView.builder(
                        itemCount: filtered.length,
                        itemBuilder: (context, i) {
                          final app = filtered[i];
                          final on = _selected.contains(app.packageName);
                          return CheckboxListTile(
                            value: on,
                            onChanged: (_) => _toggle(app.packageName),
                            secondary: _AppIcon(app: app),
                            title: Text(app.label,
                                maxLines: 1, overflow: TextOverflow.ellipsis),
                            subtitle: Text(
                              app.packageName,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(
                                  fontSize: 12, color: Colors.white54),
                            ),
                          );
                        },
                      ),
          ),
        ],
      ),
    );
  }
}

class _AppIcon extends StatelessWidget {
  const _AppIcon({required this.app});
  final InstalledApp app;

  @override
  Widget build(BuildContext context) {
    final bytes = app.iconBytes;
    if (bytes != null && bytes.isNotEmpty) {
      return ClipRRect(
        borderRadius: BorderRadius.circular(8),
        child: Image.memory(bytes, width: 40, height: 40, fit: BoxFit.cover),
      );
    }
    final letter =
        app.label.isNotEmpty ? app.label.substring(0, 1).toUpperCase() : '?';
    return CircleAvatar(
      radius: 20,
      backgroundColor: const Color(0xFF2B2F3A),
      child: Text(letter, style: const TextStyle(fontWeight: FontWeight.w600)),
    );
  }
}

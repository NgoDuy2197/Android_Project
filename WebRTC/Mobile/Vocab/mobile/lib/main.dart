
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'collection_page.dart';
import 'models.dart';
import 'native.dart';
import 'ui.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
  SystemChrome.setSystemUIOverlayStyle(
    const SystemUiOverlayStyle(statusBarColor: Colors.transparent, systemNavigationBarColor: Colors.transparent),
  );
  final store = VocabStore();
  await store.load();
  runApp(VocabApp(store: store));
}

class VocabApp extends StatelessWidget {
  const VocabApp({super.key, required this.store});
  final VocabStore store;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Từ Vựng',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: kAccent),
        useMaterial3: true,
        scaffoldBackgroundColor: Colors.transparent,
        appBarTheme: const AppBarTheme(backgroundColor: Colors.transparent, surfaceTintColor: Colors.transparent),
      ),
      builder: (context, child) => Backdrop(child: child!),
      home: HomePage(store: store),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key, required this.store});
  final VocabStore store;

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> with WidgetsBindingObserver {
  VocabStore get store => widget.store;
  int _tab = 0;
  List<WidgetConfig> _widgets = [];

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _loadWidgets();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // The widget may have marked words as learned meanwhile.
    if (state == AppLifecycleState.resumed) {
      store.load();
      _loadWidgets();
    }
  }

  Future<void> _loadWidgets() async {
    final w = await Native.widgets();
    if (mounted) setState(() => _widgets = w);
  }

  Future<void> _changed() async {
    await store.save();
    await Native.refreshWidgets();
  }

  // ------------------------------------------------------------ import/export
  Future<void> _import() async {
    final text = await Native.openJson();
    if (text == null) return;
    try {
      final (added, replaced) = await store.importJson(text);
      await Native.refreshWidgets();
      if (mounted) toast(context, 'Đã nhập: $added bộ mới, $replaced bộ được khôi phục');
    } catch (e) {
      if (mounted) toast(context, 'File JSON không hợp lệ');
    }
  }

  Future<void> _export(List<Collection> list, String name) async {
    try {
      final ok = await Native.saveJson(name, VocabStore.exportJson(list));
      if (ok && mounted) toast(context, 'Đã xuất ${list.length} bộ từ');
    } catch (e) {
      if (mounted) toast(context, 'Xuất file thất bại: $e');
    }
  }

  String _stamp() {
    final n = DateTime.now();
    String two(int v) => v.toString().padLeft(2, '0');
    return '${n.year}${two(n.month)}${two(n.day)}_${two(n.hour)}${two(n.minute)}';
  }

  /// Speech-rate slider with instant preview; saved natively for the widget too.
  Future<void> _speed() async {
    var rate = await Native.rate();
    if (!mounted) return;
    await showDialog<void>(
      context: context,
      builder: (c) => StatefulBuilder(
        builder: (c, set) => AlertDialog(
          title: const Text('Tốc độ đọc'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text('${rate.toStringAsFixed(2)}×  ·  ${rate < 0.75 ? 'Chậm' : rate <= 1.05 ? 'Vừa' : 'Nhanh'}',
                  style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700)),
              Slider(
                min: 0.4,
                max: 1.4,
                divisions: 20,
                value: rate.clamp(0.4, 1.4),
                onChanged: (v) => set(() => rate = v),
                onChangeEnd: (v) => Native.setRate(v),
              ),
              Wrap(
                spacing: 8,
                children: [
                  for (final (label, text, locale) in [
                    ('🇬🇧 Nghe thử', 'Good morning, how are you today?', 'en-US'),
                    ('🇨🇳 试听', '早上好，你今天好吗？', 'zh-CN'),
                    ('🇯🇵 試聴', 'おはようございます、お元気ですか？', 'ja-JP'),
                  ])
                    ActionChip(
                      label: Text(label),
                      onPressed: () async {
                        await Native.setRate(rate);
                        await Native.speak(text, locale);
                      },
                    ),
                ],
              ),
            ],
          ),
          actions: [TextButton(onPressed: () => Navigator.pop(c), child: const Text('Xong'))],
        ),
      ),
    );
  }

  // ----------------------------------------------------------------- create
  Future<void> _create() async {
    final c = Collection(id: newId(), name: '', fields: [Field('Từ', 'en'), Field('Nghĩa', 'vi')]);
    final ok = await editCollectionInfo(context, c, isNew: true);
    if (!ok || !mounted) return;
    store.collections.add(c);
    await _changed();
    if (mounted) _open(c);
  }

  void _open(Collection c) {
    Navigator.of(context).push(
      MaterialPageRoute(builder: (_) => CollectionPage(store: store, collection: c, onChanged: _changed)),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(_tab == 0 ? 'Từ Vựng' : 'Widget', style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 28)),
        actions: [
          if (_tab == 0)
            PopupMenuButton<String>(
              icon: const Icon(Icons.more_horiz_rounded),
              onSelected: (v) {
                if (v == 'import') _import();
                if (v == 'export') _export(store.collections, 'vocab_backup_${_stamp()}.json');
                if (v == 'speed') _speed();
              },
              itemBuilder: (_) => const [
                PopupMenuItem(value: 'import', child: ListTile(leading: Icon(Icons.file_download_outlined), title: Text('Nhập JSON (khôi phục)'))),
                PopupMenuItem(value: 'export', child: ListTile(leading: Icon(Icons.file_upload_outlined), title: Text('Xuất tất cả (sao lưu)'))),
                PopupMenuItem(value: 'speed', child: ListTile(leading: Icon(Icons.speed_rounded), title: Text('Tốc độ đọc'))),
              ],
            ),
        ],
      ),
      floatingActionButton: _tab == 0
          ? FloatingActionButton.extended(onPressed: _create, icon: const Icon(Icons.add), label: const Text('Bộ từ mới'))
          : null,
      bottomNavigationBar: NavigationBar(
        backgroundColor: Colors.white.withValues(alpha: 0.7),
        selectedIndex: _tab,
        onDestinationSelected: (i) {
          setState(() => _tab = i);
          if (i == 1) _loadWidgets();
        },
        destinations: const [
          NavigationDestination(icon: Icon(Icons.collections_bookmark_outlined), selectedIcon: Icon(Icons.collections_bookmark), label: 'Bộ từ'),
          NavigationDestination(icon: Icon(Icons.widgets_outlined), selectedIcon: Icon(Icons.widgets), label: 'Widget'),
        ],
      ),
      body: ListenableBuilder(
        listenable: store,
        builder: (context, _) => _tab == 0 ? _collections() : _widgetTab(),
      ),
    );
  }

  Widget _collections() {
    if (store.collections.isEmpty) {
      return const Center(child: Text('Chưa có bộ từ nào.\nBấm "Bộ từ mới" hoặc nhập file JSON.', textAlign: TextAlign.center));
    }
    return ListView.separated(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 100),
      itemCount: store.collections.length,
      separatorBuilder: (_, _) => const SizedBox(height: 12),
      itemBuilder: (context, i) {
        final c = store.collections[i];
        final total = c.words.length;
        final done = c.learnedCount;
        return Glass(
          onTap: () => _open(c),
          child: Row(
            children: [
              Container(
                width: 52,
                height: 52,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(16),
                  gradient: const LinearGradient(colors: [kAccent, kAccent2]),
                ),
                child: Text(
                  c.fields.map((f) => Lang.of(f.lang).flag).toSet().take(3).join(),
                  style: const TextStyle(fontSize: 16),
                ),
              ),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(c.name, style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700)),
                    const SizedBox(height: 2),
                    Text(
                      '${c.fields.map((f) => f.name).join(' · ')}  —  $total từ',
                      style: const TextStyle(color: Colors.black54, fontSize: 13),
                    ),
                    const SizedBox(height: 8),
                    ClipRRect(
                      borderRadius: BorderRadius.circular(4),
                      child: LinearProgressIndicator(
                        value: total == 0 ? 0 : done / total,
                        minHeight: 6,
                        backgroundColor: Colors.black12,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text('Đã thuộc $done/$total', style: const TextStyle(fontSize: 12, color: Colors.black54)),
                  ],
                ),
              ),
              PopupMenuButton<String>(
                onSelected: (v) async {
                  if (v == 'export') {
                    _export([c], '${c.name.replaceAll(RegExp(r'[^\w\- ]'), '').trim().replaceAll(' ', '_')}.json');
                  } else if (v == 'delete' && await confirm(context, 'Xoá bộ từ?', 'Xoá "${c.name}" và ${c.words.length} từ?')) {
                    store.collections.remove(c);
                    await _changed();
                  }
                },
                itemBuilder: (_) => const [
                  PopupMenuItem(value: 'export', child: Text('Xuất JSON')),
                  PopupMenuItem(value: 'delete', child: Text('Xoá', style: TextStyle(color: Colors.redAccent))),
                ],
              ),
            ],
          ),
        );
      },
    );
  }

  Widget _widgetTab() {
    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
      children: [
        Glass(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text('Widget từ vựng', style: TextStyle(fontSize: 17, fontWeight: FontWeight.w700)),
              const SizedBox(height: 6),
              const Text(
                'Trên widget: 🔊 đọc từ · ✓ đánh dấu đã thuộc (không hiện lại) · ↻ đổi từ. '
                'Chạm vào từng dòng để nghe dòng đó.',
                style: TextStyle(color: Colors.black54),
              ),
              const SizedBox(height: 12),
              FilledButton.icon(
                onPressed: () async {
                  final ok = await Native.pinWidget();
                  if (!ok && mounted) {
                    toast(context, 'Giữ màn hình chính → Widgets → Từ Vựng để thêm');
                  }
                },
                icon: const Icon(Icons.add_to_home_screen),
                label: const Text('Thêm widget ra màn hình chính'),
              ),
            ],
          ),
        ),
        const SizedBox(height: 14),
        if (_widgets.isEmpty)
          const Padding(
            padding: EdgeInsets.all(24),
            child: Text('Chưa có widget nào trên màn hình chính.', textAlign: TextAlign.center),
          ),
        for (final w in _widgets) ...[_widgetCard(w), const SizedBox(height: 12)],
      ],
    );
  }

  Widget _widgetCard(WidgetConfig w) {
    final valid = store.byId(w.collectionId) != null;
    Future<void> apply() async {
      await Native.setWidget(w);
      setState(() {});
    }

    return Glass(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Widget #${w.id}', style: const TextStyle(fontWeight: FontWeight.w700)),
          const SizedBox(height: 8),
          DropdownButtonFormField<String>(
            initialValue: valid ? w.collectionId : null,
            isExpanded: true,
            decoration: const InputDecoration(labelText: 'Hiển thị từ bộ', border: OutlineInputBorder()),
            items: [for (final c in store.collections) DropdownMenuItem(value: c.id, child: Text(c.name))],
            onChanged: (v) {
              if (v == null) return;
              w.collectionId = v;
              final c = store.byId(v)!;
              w.lines = w.lines.clamp(1, c.fields.length.clamp(1, 3));
              apply();
            },
          ),
          const SizedBox(height: 12),
          const Text('Số dòng hiển thị'),
          const SizedBox(height: 6),
          SegmentedButton<int>(
            segments: const [
              ButtonSegment(value: 1, label: Text('1 dòng')),
              ButtonSegment(value: 2, label: Text('2 dòng')),
              ButtonSegment(value: 3, label: Text('3 dòng')),
            ],
            selected: {w.lines},
            onSelectionChanged: (s) {
              w.lines = s.first;
              apply();
            },
          ),
          const SizedBox(height: 12),
          DropdownButtonFormField<int>(
            initialValue: w.autoMinutes,
            decoration: const InputDecoration(labelText: 'Tự động đổi từ', border: OutlineInputBorder()),
            items: const [
              DropdownMenuItem(value: 0, child: Text('Tắt (chỉ bấm "Đổi từ")')),
              DropdownMenuItem(value: 30, child: Text('Mỗi 30 phút')),
              DropdownMenuItem(value: 60, child: Text('Mỗi 1 giờ')),
              DropdownMenuItem(value: 180, child: Text('Mỗi 3 giờ')),
              DropdownMenuItem(value: 1440, child: Text('Mỗi ngày')),
            ],
            onChanged: (v) {
              w.autoMinutes = v ?? 0;
              apply();
            },
          ),
        ],
      ),
    );
  }
}

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'editor_page.dart';
import 'models.dart';
import 'native.dart';
import 'preview.dart';
import 'ui.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
  SystemChrome.setSystemUIOverlayStyle(
    const SystemUiOverlayStyle(statusBarColor: Colors.transparent, systemNavigationBarColor: Colors.transparent),
  );
  final store = FrameStore();
  await store.load();
  runApp(FrameApp(store: store));
}

class FrameApp extends StatelessWidget {
  const FrameApp({super.key, required this.store});
  final FrameStore store;

  @override
  Widget build(BuildContext context) => MaterialApp(
    title: 'Khung Ảnh',
    debugShowCheckedModeBanner: false,
    theme: ThemeData(
      useMaterial3: true,
      colorScheme: ColorScheme.fromSeed(seedColor: kAccent),
      scaffoldBackgroundColor: Colors.transparent,
      appBarTheme: const AppBarTheme(backgroundColor: Colors.transparent, surfaceTintColor: Colors.transparent),
    ),
    builder: (context, child) => Backdrop(child: child!),
    home: HomePage(store: store),
  );
}

class HomePage extends StatefulWidget {
  const HomePage({super.key, required this.store});
  final FrameStore store;

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> with WidgetsBindingObserver {
  FrameStore get store => widget.store;
  List<Map<String, dynamic>> _widgets = [];
  int _rev = 0;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _loadWidgets();
    Native.refreshAll();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _loadWidgets();
  }

  Future<void> _loadWidgets() async {
    final w = await Native.widgets();
    if (mounted) setState(() => _widgets = w);
  }

  Future<void> _saved() async {
    await store.save();
    await Native.refreshAll();
    setState(() => _rev++);
  }

  Future<void> _edit(Frame f, {bool isNew = false}) async {
    final edited = await Navigator.of(context).push<Frame>(
      MaterialPageRoute(builder: (_) => EditorPage(frame: f.copy(), isNew: isNew)),
    );
    if (edited == null) return;
    final i = store.frames.indexWhere((e) => e.id == edited.id);
    if (i >= 0) {
      store.frames[i] = edited;
    } else {
      store.frames.add(edited);
    }
    await _saved();
    if (isNew && mounted) _offerPin(edited);
  }

  Future<void> _offerPin(Frame f) async {
    final ok = await Native.pin(f.id);
    if (!ok && mounted) toast(context, 'Giữ màn hình chính → Widgets → Khung Ảnh để thêm');
  }

  Future<void> _menu(Frame f) async {
    final v = await showModalBottomSheet<String>(
      context: context,
      backgroundColor: Colors.transparent,
      builder: (c) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Glass(
            padding: EdgeInsets.zero,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                ListTile(leading: const Icon(Icons.edit_outlined), title: const Text('Sửa'), onTap: () => Navigator.pop(c, 'edit')),
                ListTile(leading: const Icon(Icons.add_to_home_screen), title: const Text('Thêm widget ra màn hình'), onTap: () => Navigator.pop(c, 'pin')),
                ListTile(leading: const Icon(Icons.copy_rounded), title: const Text('Nhân bản'), onTap: () => Navigator.pop(c, 'dup')),
                ListTile(
                  leading: const Icon(Icons.delete_outline, color: Colors.redAccent),
                  title: const Text('Xoá', style: TextStyle(color: Colors.redAccent)),
                  onTap: () => Navigator.pop(c, 'del'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
    if (!mounted) return;
    switch (v) {
      case 'edit':
        _edit(f);
      case 'pin':
        _offerPin(f);
      case 'dup':
        store.frames.add(f.copy(id: 'f${DateTime.now().microsecondsSinceEpoch}', name: '${f.name} (bản sao)'));
        _saved();
      case 'del':
        if (await confirm(context, 'Xoá khung?', 'Widget đang dùng "${f.name}" sẽ chuyển sang khung khác.')) {
          store.frames.removeWhere((e) => e.id == f.id);
          _saved();
        }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Khung Ảnh', style: TextStyle(fontWeight: FontWeight.w800, fontSize: 28))),
      floatingActionButton: GlassButton(
        icon: Icons.add_photo_alternate_outlined,
        label: 'Khung mới',
        onPressed: () => _edit(Frame.create(name: 'Khung ${store.frames.length + 1}'), isNew: true),
      ),
      body: ListenableBuilder(
        listenable: store,
        builder: (context, _) => CustomScrollView(
          slivers: [
            SliverPadding(
              padding: const EdgeInsets.fromLTRB(16, 4, 16, 8),
              sliver: SliverGrid.count(
                crossAxisCount: 2,
                mainAxisSpacing: 14,
                crossAxisSpacing: 14,
                childAspectRatio: 0.78,
                children: [for (final f in store.frames) _card(f)],
              ),
            ),
            SliverToBoxAdapter(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(16, 12, 16, 100),
                child: _widgetsSection(),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _card(Frame f) {
    final used = _widgets.where((w) => w['frameId'] == f.id).length;
    return Glass(
      padding: const EdgeInsets.all(10),
      onTap: () => _edit(f),
      onLongPress: () => _menu(f),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: Center(
              child: FittedBox(child: FramePreview(frame: f, sizeDp: const Size(170, 170), revision: _rev)),
            ),
          ),
          Row(
            children: [
              Expanded(
                child: Text(f.name, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w700)),
              ),
              GestureDetector(onTap: () => _menu(f), child: const Icon(Icons.more_horiz, color: Colors.black54)),
            ],
          ),
          Text(
            '${f.get<int>('count', 1)} ảnh · ${f.get<String>('order', 'sequential') == 'random' ? 'ngẫu nhiên' : 'tuần tự'}'
            '${used > 0 ? ' · $used widget' : ''}',
            style: const TextStyle(fontSize: 12, color: Colors.black54),
          ),
        ],
      ),
    );
  }

  Widget _widgetsSection() {
    return Glass(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('Widget trên màn hình chính', style: TextStyle(fontWeight: FontWeight.w700, fontSize: 16)),
          const SizedBox(height: 4),
          const Text(
            'Kéo đổi cỡ widget thì khung tự vẽ lại cho vừa. Giữ một khung ở trên để thêm widget.',
            style: TextStyle(color: Colors.black54, fontSize: 12),
          ),
          if (_widgets.isEmpty)
            const Padding(padding: EdgeInsets.only(top: 10), child: Text('Chưa có widget nào.')),
          for (final w in _widgets)
            Padding(
              padding: const EdgeInsets.only(top: 10),
              child: Row(
                children: [
                  Expanded(
                    child: DropdownButtonFormField<String>(
                      initialValue: store.byId(w['frameId'] as String) != null ? w['frameId'] as String : null,
                      isExpanded: true,
                      decoration: InputDecoration(
                        labelText: 'Widget #${w['id']} · ${w['w']}×${w['h']} dp',
                        border: const OutlineInputBorder(),
                        isDense: true,
                      ),
                      items: [for (final f in store.frames) DropdownMenuItem(value: f.id, child: Text(f.name))],
                      onChanged: (v) async {
                        if (v == null) return;
                        await Native.assign(w['id'] as int, v);
                        _loadWidgets();
                      },
                    ),
                  ),
                  IconButton(
                    tooltip: 'Đổi ảnh',
                    icon: const Icon(Icons.skip_next_rounded, color: kAccent),
                    onPressed: () => Native.next(w['id'] as int),
                  ),
                ],
              ),
            ),
          const SizedBox(height: 12),
          GlassButton(
            icon: Icons.add_to_home_screen,
            label: 'Thêm widget',
            onPressed: store.frames.isEmpty ? null : () => _offerPin(store.frames.first),
          ),
        ],
      ),
    );
  }
}

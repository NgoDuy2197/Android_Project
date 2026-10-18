import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:url_launcher/url_launcher.dart';

import 'gemini.dart';
import 'models.dart';
import 'native.dart';
import 'settings_page.dart';
import 'ui.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
  final config = AppConfig();
  await config.load();
  await Native.configChanged(); // make sure the hourly job is scheduled
  runApp(NewsApp(config: config));
}

class NewsApp extends StatelessWidget {
  const NewsApp({super.key, required this.config});
  final AppConfig config;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: config,
      builder: (context, _) {
        final t = config.palette;
        SystemChrome.setSystemUIOverlayStyle(
          (t.dark ? SystemUiOverlayStyle.light : SystemUiOverlayStyle.dark).copyWith(
            statusBarColor: Colors.transparent,
            systemNavigationBarColor: Colors.transparent,
          ),
        );
        return MaterialApp(
          title: 'Tin Tức',
          debugShowCheckedModeBanner: false,
          theme: ThemeData(
            useMaterial3: true,
            colorScheme: ColorScheme.fromSeed(seedColor: t.accent, brightness: t.dark ? Brightness.dark : Brightness.light),
            scaffoldBackgroundColor: Colors.transparent,
            appBarTheme: const AppBarTheme(backgroundColor: Colors.transparent, surfaceTintColor: Colors.transparent),
          ),
          builder: (context, child) => ThemedBackground(config: config, child: child!),
          home: HomePage(config: config),
        );
      },
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key, required this.config});
  final AppConfig config;

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> with WidgetsBindingObserver {
  AppConfig get config => widget.config;
  List<NewsItem> _items = [];
  DateTime? _updated;
  bool _loading = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _reload().then((_) {
      // First launch or stale data: fetch right away.
      if (_updated == null || DateTime.now().difference(_updated!).inMinutes >= config.intervalMin) _refresh();
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // The widget / background job may have changed news.json.
    if (state == AppLifecycleState.resumed) _reload();
  }

  Future<void> _reload() async {
    final (items, updated) = await NewsStore.load();
    final feedIds = config.feeds.where((f) => f.enabled).map((f) => f.id).toSet();
    if (!mounted) return;
    setState(() {
      _items = items.where((i) => feedIds.contains(i.feedId)).toList();
      _updated = updated;
    });
  }

  Future<void> _refresh() async {
    if (_loading) return;
    setState(() => _loading = true);
    final n = await Native.fetchNow();
    await _reload();
    if (!mounted) return;
    setState(() => _loading = false);
    if (n < 0) {
      toast(context, 'Không tải được tin (kiểm tra mạng)');
    } else if (n > 0) {
      toast(context, 'Có $n tin mới');
    }
  }

  Future<void> _markRead(NewsItem item) async {
    if (item.read) return;
    setState(() => item.read = true);
    await Native.markRead([item.id]);
  }

  Future<void> _open(NewsItem item) async {
    _markRead(item);
    final uri = Uri.tryParse(item.link);
    if (uri == null) return;
    if (!await launchUrl(uri, mode: LaunchMode.inAppBrowserView)) {
      await launchUrl(uri, mode: LaunchMode.externalApplication);
    }
  }

  /// Sends every unread headline to Gemini, shows the summary, then marks them read.
  Future<void> _aiSummary() async {
    final unread = _items.where((i) => !i.read).toList();
    if (config.geminiKey.trim().isEmpty) {
      final go = await showDialog<bool>(
        context: context,
        builder: (c) => AlertDialog(
          title: const Text('Chưa có API key Gemini'),
          content: const Text('Vào Cài đặt → AI tóm tắt để nhập API key (lấy miễn phí tại aistudio.google.com).'),
          actions: [
            TextButton(onPressed: () => Navigator.pop(c, false), child: const Text('Để sau')),
            TextButton(onPressed: () => Navigator.pop(c, true), child: const Text('Mở Cài đặt')),
          ],
        ),
      );
      if (go == true && mounted) {
        await Navigator.of(context).push(MaterialPageRoute(builder: (_) => SettingsPage(config: config)));
        _reload();
      }
      return;
    }
    if (unread.isEmpty) {
      toast(context, 'Không có tin chưa đọc để tóm tắt');
      return;
    }
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (_) => _SummarySheet(
        theme: config.palette,
        apiKey: config.geminiKey.trim(),
        items: unread,
        onSummarized: () async {
          // Only after a successful summary: everything that was summarised is read.
          final ids = unread.take(Gemini.maxTitles).map((i) => i.id).toList();
          setState(() {
            for (final i in unread.take(Gemini.maxTitles)) {
              i.read = true;
            }
          });
          await Native.markRead(ids);
        },
      ),
    );
  }

  Future<void> _markAllRead() async {
    final ids = _items.where((i) => !i.read).map((i) => i.id).toList();
    if (ids.isEmpty) return;
    setState(() {
      for (final i in _items) {
        i.read = true;
      }
    });
    await Native.markRead(ids);
  }

  @override
  Widget build(BuildContext context) {
    final t = config.palette;
    final unread = _items.where((i) => !i.read).length;
    final shown = config.showAll ? _items : _items.where((i) => !i.read).toList();
    return Scaffold(
      body: RefreshIndicator(
        color: t.accent,
        onRefresh: _refresh,
        child: CustomScrollView(
          physics: const AlwaysScrollableScrollPhysics(parent: BouncingScrollPhysics()),
          slivers: [
            SliverAppBar(
              pinned: true,
              expandedHeight: t.yahoo ? 132 : 120,
              backgroundColor: t.yahoo ? const Color(0xFF400090) : t.bg.first.withValues(alpha: 0.85),
              flexibleSpace: FlexibleSpaceBar(
                titlePadding: const EdgeInsetsDirectional.only(start: 20, bottom: 14),
                title: t.yahoo
                    ? const Text(
                        'Tin Tức!',
                        style: TextStyle(
                          color: Colors.white,
                          fontWeight: FontWeight.w900,
                          fontStyle: FontStyle.italic,
                          letterSpacing: -0.5,
                        ),
                      )
                    : Text('Tin Tức', style: TextStyle(color: t.text, fontWeight: FontWeight.w800)),
                background: t.yahoo
                    ? const DecoratedBox(
                        decoration: BoxDecoration(
                          gradient: LinearGradient(colors: [Color(0xFF400090), Color(0xFF6001D2), Color(0xFF7B2FF7)]),
                        ),
                      )
                    : null,
              ),
              actions: [
                if (_loading)
                  const Padding(
                    padding: EdgeInsets.all(14),
                    child: SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2)),
                  ),
                IconButton(
                  tooltip: 'Tóm tắt AI tin chưa đọc',
                  color: t.yahoo ? Colors.white : t.text,
                  icon: const Icon(Icons.auto_awesome_rounded),
                  onPressed: _aiSummary,
                ),
                IconButton(
                  tooltip: 'Đánh dấu tất cả đã đọc',
                  color: t.yahoo ? Colors.white : t.text,
                  icon: const Icon(Icons.done_all_rounded),
                  onPressed: _markAllRead,
                ),
                IconButton(
                  tooltip: 'Cài đặt',
                  color: t.yahoo ? Colors.white : t.text,
                  icon: const Icon(Icons.settings_outlined),
                  onPressed: () async {
                    await Navigator.of(context).push(MaterialPageRoute(builder: (_) => SettingsPage(config: config)));
                    _reload();
                  },
                ),
              ],
            ),
            SliverToBoxAdapter(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(16, 12, 16, 6),
                // Filter full width; "last updated" on its own line so it never gets cut off.
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    SegmentedButton<bool>(
                      segments: [
                        ButtonSegment(value: false, label: Text('Chưa đọc ($unread)')),
                        ButtonSegment(value: true, label: Text('Tất cả (${_items.length})')),
                      ],
                      selected: {config.showAll},
                      showSelectedIcon: false,
                      onSelectionChanged: (s) {
                        config.showAll = s.first;
                        config.save();
                      },
                    ),
                    if (_updated != null)
                      Padding(
                        padding: const EdgeInsets.only(top: 6, right: 4),
                        child: Text(
                          'Cập nhật ${timeAgo(_updated!)}',
                          textAlign: TextAlign.right,
                          style: TextStyle(color: t.sub, fontSize: 12),
                        ),
                      ),
                  ],
                ),
              ),
            ),
            if (shown.isEmpty)
              SliverFillRemaining(
                hasScrollBody: false,
                child: Center(
                  child: Text(
                    _loading ? 'Đang tải tin…' : (config.showAll ? 'Chưa có tin.\nKéo xuống để tải.' : 'Bạn đã đọc hết tin 🎉\nKéo xuống để tải thêm.'),
                    textAlign: TextAlign.center,
                    style: TextStyle(color: t.sub),
                  ),
                ),
              )
            else
              SliverPadding(
                padding: const EdgeInsets.fromLTRB(16, 6, 16, 40),
                sliver: SliverList.separated(
                  itemCount: shown.length,
                  separatorBuilder: (_, _) => const SizedBox(height: 12),
                  itemBuilder: (context, i) => _card(shown[i], t),
                ),
              ),
          ],
        ),
      ),
    );
  }

  Widget _card(NewsItem item, NewsTheme t) {
    final card = Opacity(
      opacity: item.read ? 0.55 : 1,
      child: Glass(
        theme: t,
        padding: const EdgeInsets.all(12),
        onTap: () => _open(item),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Container(
                        width: 7,
                        height: 7,
                        decoration: BoxDecoration(color: item.read ? t.sub : t.accent, shape: BoxShape.circle),
                      ),
                      const SizedBox(width: 6),
                      Flexible(
                        child: Text(
                          '${item.source} · ${timeAgo(item.published)}',
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(color: t.sub, fontSize: 12, fontWeight: FontWeight.w600),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 6),
                  Text(
                    item.title,
                    maxLines: 3,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: t.yahoo ? const Color(0xFF1A0DAB) : t.text,
                      fontSize: 16,
                      fontWeight: FontWeight.w700,
                      height: 1.25,
                    ),
                  ),
                  if (item.summary.isNotEmpty) ...[
                    const SizedBox(height: 4),
                    Text(item.summary, maxLines: 2, overflow: TextOverflow.ellipsis, style: TextStyle(color: t.sub, fontSize: 13)),
                  ],
                ],
              ),
            ),
            if (item.image.isNotEmpty) ...[
              const SizedBox(width: 10),
              ClipRRect(
                borderRadius: BorderRadius.circular(14),
                child: Image.network(
                  item.image,
                  width: 92,
                  height: 92,
                  fit: BoxFit.cover,
                  cacheWidth: 276,
                  errorBuilder: (_, _, _) => const SizedBox(width: 92, height: 92),
                ),
              ),
            ],
          ],
        ),
      ),
    );
    // Swipe either way = read. The card fades and shrinks as it is dragged;
    // in "Chưa đọc" it then glides away, in "Tất cả" it springs back dimmed.
    return _SwipeToRead(
      key: ValueKey('${item.id}_${config.showAll}'),
      theme: t,
      onSwiped: () async {
        await _markRead(item);
        return !config.showAll;
      },
      child: card,
    );
  }
}

/// Dismissible whose card fades out (and shrinks a little) with the drag
/// progress, while a soft "Đã đọc" hint fades in behind it.
class _SwipeToRead extends StatefulWidget {
  const _SwipeToRead({super.key, required this.theme, required this.onSwiped, required this.child});
  final NewsTheme theme;
  final Future<bool> Function() onSwiped;
  final Widget child;

  @override
  State<_SwipeToRead> createState() => _SwipeToReadState();
}

class _SwipeToReadState extends State<_SwipeToRead> {
  double _p = 0; // 0 = resting, 1 = fully dragged out

  Widget _hint(Alignment a) => Align(
    alignment: a,
    child: Padding(
      padding: const EdgeInsets.symmetric(horizontal: 26),
      child: Opacity(
        opacity: (_p * 2.2).clamp(0.0, 1.0),
        child: Transform.scale(
          scale: 0.8 + 0.2 * (_p * 2).clamp(0.0, 1.0),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(Icons.done_all_rounded, color: widget.theme.accent),
              const SizedBox(width: 6),
              Text('Đã đọc', style: TextStyle(color: widget.theme.accent, fontWeight: FontWeight.w700)),
            ],
          ),
        ),
      ),
    ),
  );

  @override
  Widget build(BuildContext context) {
    final fade = (1 - _p * 1.25).clamp(0.0, 1.0);
    return Dismissible(
      key: ValueKey('swipe_${widget.key}'),
      dismissThresholds: const {DismissDirection.startToEnd: 0.32, DismissDirection.endToStart: 0.32},
      movementDuration: const Duration(milliseconds: 280),
      resizeDuration: const Duration(milliseconds: 260),
      background: _hint(Alignment.centerLeft),
      secondaryBackground: _hint(Alignment.centerRight),
      onUpdate: (d) => setState(() => _p = d.progress),
      confirmDismiss: (_) => widget.onSwiped(),
      child: Opacity(
        opacity: fade,
        child: Transform.scale(scale: 1 - 0.06 * _p.clamp(0.0, 1.0), child: widget.child),
      ),
    );
  }
}

/// Bottom sheet: calls Gemini, then shows the summary (bold topics + bullets).
class _SummarySheet extends StatefulWidget {
  const _SummarySheet({required this.theme, required this.apiKey, required this.items, required this.onSummarized});
  final NewsTheme theme;
  final String apiKey;
  final List<NewsItem> items;
  final Future<void> Function() onSummarized;

  @override
  State<_SummarySheet> createState() => _SummarySheetState();
}

class _SummarySheetState extends State<_SummarySheet> {
  String? _text;
  String? _error;

  @override
  void initState() {
    super.initState();
    _run();
  }

  Future<void> _run() async {
    setState(() {
      _text = null;
      _error = null;
    });
    try {
      final s = await Gemini.summarize(widget.apiKey, widget.items);
      if (!mounted) return;
      setState(() => _text = s);
      await widget.onSummarized();
    } catch (e) {
      if (mounted) setState(() => _error = '$e');
    }
  }

  /// Minimal markdown: "**topic**" lines, "- "/"* " bullets, inline **bold**.
  List<Widget> _render(String text, NewsTheme t) {
    final out = <Widget>[];
    TextSpan inline(String s, TextStyle base) {
      final spans = <TextSpan>[];
      final re = RegExp(r'\*\*(.+?)\*\*');
      var last = 0;
      for (final m in re.allMatches(s)) {
        if (m.start > last) spans.add(TextSpan(text: s.substring(last, m.start)));
        spans.add(TextSpan(text: m.group(1), style: const TextStyle(fontWeight: FontWeight.w800)));
        last = m.end;
      }
      if (last < s.length) spans.add(TextSpan(text: s.substring(last)));
      return TextSpan(style: base, children: spans);
    }

    final body = TextStyle(color: t.text, fontSize: 15, height: 1.4);
    for (final raw in text.split('\n')) {
      final line = raw.trimRight();
      if (line.trim().isEmpty) {
        out.add(const SizedBox(height: 6));
      } else if (RegExp(r'^\s*#+\s').hasMatch(line) || RegExp(r'^\s*\*\*[^*]+\*\*:?\s*$').hasMatch(line)) {
        final h = line.replaceAll(RegExp(r'^\s*#+\s*'), '').replaceAll('**', '').trim();
        out.add(Padding(
          padding: const EdgeInsets.only(top: 8, bottom: 2),
          child: Text(h, style: TextStyle(color: t.accent, fontSize: 16, fontWeight: FontWeight.w800)),
        ));
      } else if (RegExp(r'^\s*[-*•]\s+').hasMatch(line)) {
        final s = line.replaceFirst(RegExp(r'^\s*[-*•]\s+'), '');
        out.add(Padding(
          padding: const EdgeInsets.only(left: 4, top: 2),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Padding(
                padding: const EdgeInsets.only(top: 8, right: 8),
                child: Container(width: 5, height: 5, decoration: BoxDecoration(color: t.accent, shape: BoxShape.circle)),
              ),
              Expanded(child: Text.rich(inline(s, body))),
            ],
          ),
        ));
      } else {
        out.add(Text.rich(inline(line, body)));
      }
    }
    return out;
  }

  @override
  Widget build(BuildContext context) {
    final t = widget.theme;
    final n = widget.items.length.clamp(0, Gemini.maxTitles);
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(10, 0, 10, 10),
        child: ConstrainedBox(
          constraints: BoxConstraints(maxHeight: MediaQuery.sizeOf(context).height * 0.8),
          child: Glass(
            theme: t,
            radius: 28,
            padding: const EdgeInsets.fromLTRB(18, 10, 18, 14),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Center(
                  child: Container(
                    width: 40,
                    height: 5,
                    margin: const EdgeInsets.only(bottom: 10),
                    decoration: BoxDecoration(color: t.sub.withValues(alpha: 0.4), borderRadius: BorderRadius.circular(3)),
                  ),
                ),
                Row(
                  children: [
                    Icon(Icons.auto_awesome_rounded, color: t.accent),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text('Tóm tắt $n tin', style: TextStyle(color: t.text, fontSize: 18, fontWeight: FontWeight.w800)),
                    ),
                    if (_text != null)
                      IconButton(
                        tooltip: 'Sao chép',
                        icon: Icon(Icons.copy_rounded, color: t.sub),
                        onPressed: () {
                          Clipboard.setData(ClipboardData(text: _text!));
                          toast(context, 'Đã sao chép');
                        },
                      ),
                  ],
                ),
                Text(Gemini.model, style: TextStyle(color: t.sub, fontSize: 11)),
                const SizedBox(height: 8),
                if (_text == null && _error == null)
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: 30),
                    child: Center(
                      child: Column(
                        children: [
                          CircularProgressIndicator(color: t.accent),
                          const SizedBox(height: 12),
                          Text('AI đang đọc $n tiêu đề…', style: TextStyle(color: t.sub)),
                        ],
                      ),
                    ),
                  ),
                if (_error != null) ...[
                  Text(_error!, style: const TextStyle(color: Colors.redAccent)),
                  const SizedBox(height: 4),
                  Text('Các tin vẫn giữ trạng thái chưa đọc.', style: TextStyle(color: t.sub, fontSize: 12)),
                  TextButton.icon(onPressed: _run, icon: const Icon(Icons.refresh), label: const Text('Thử lại')),
                ],
                if (_text != null) ...[
                  Flexible(child: SingleChildScrollView(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: _render(_text!, t)))),
                  const SizedBox(height: 8),
                  Row(
                    children: [
                      Icon(Icons.done_all_rounded, size: 16, color: t.accent),
                      const SizedBox(width: 6),
                      Text('Đã đánh dấu $n tin là đã đọc', style: TextStyle(color: t.sub, fontSize: 12)),
                    ],
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}
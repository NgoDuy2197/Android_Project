import 'dart:async';
import 'dart:io';
import 'dart:ui';

import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_localizations/flutter_localizations.dart';

import 'editor_page.dart';
import 'models.dart';
import 'native.dart';

const kAccent = Color(0xFF2E7CF6);

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
  SystemChrome.setSystemUIOverlayStyle(
    const SystemUiOverlayStyle(statusBarColor: Colors.transparent, systemNavigationBarColor: Colors.transparent),
  );
  runApp(const ReminderApp());
}

class ReminderApp extends StatelessWidget {
  const ReminderApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Nhắc Nhở',
      debugShowCheckedModeBanner: false,
      // Vietnamese labels for the iOS-style pickers (giờ / phút / giây, tháng…).
      locale: const Locale('vi'),
      supportedLocales: const [Locale('vi'), Locale('en')],
      localizationsDelegates: GlobalMaterialLocalizations.delegates,
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(seedColor: kAccent),
        scaffoldBackgroundColor: Colors.transparent,
        appBarTheme: const AppBarTheme(backgroundColor: Colors.transparent, surfaceTintColor: Colors.transparent),
      ),
      builder: (context, child) => DecoratedBox(
        decoration: const BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [Color(0xFFDFFBF1), Color(0xFFDCEBFF), Color(0xFFEAE6FF)],
          ),
        ),
        child: child,
      ),
      home: const HomePage(),
    );
  }
}

/// Frosted white card.
class Glass extends StatelessWidget {
  const Glass({super.key, required this.child, this.padding = const EdgeInsets.all(14), this.onTap});
  final Widget child;
  final EdgeInsetsGeometry padding;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return ClipRRect(
      borderRadius: BorderRadius.circular(22),
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: 20, sigmaY: 20),
        child: Material(
          color: Colors.white.withValues(alpha: 0.64),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(22),
            side: BorderSide(color: Colors.white.withValues(alpha: 0.9)),
          ),
          child: InkWell(onTap: onTap, child: Padding(padding: padding, child: child)),
        ),
      ),
    );
  }
}

/// iOS-style translucent pill button: frosted white, thin light border,
/// tinted label (no solid fill). [tint] colours the text/icon and a faint wash.
class GlassButton extends StatelessWidget {
  const GlassButton({
    super.key,
    required this.label,
    this.icon,
    this.onPressed,
    this.tint = kAccent,
    this.big = false,
  });

  final String label;
  final IconData? icon;
  final VoidCallback? onPressed;
  final Color tint;
  final bool big;

  @override
  Widget build(BuildContext context) {
    final enabled = onPressed != null;
    final color = enabled ? tint : Colors.black38;
    return ClipRRect(
      borderRadius: BorderRadius.circular(40),
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: 18, sigmaY: 18),
        child: Material(
          color: Color.alphaBlend(tint.withValues(alpha: enabled ? 0.08 : 0), Colors.white.withValues(alpha: 0.5)),
          shape: StadiumBorder(side: BorderSide(color: Colors.white.withValues(alpha: 0.95))),
          child: InkWell(
            onTap: onPressed,
            child: Padding(
              padding: EdgeInsets.symmetric(horizontal: big ? 22 : 16, vertical: big ? 15 : 10),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  if (icon != null) ...[Icon(icon, color: color, size: big ? 22 : 20), const SizedBox(width: 8)],
                  Text(label, style: TextStyle(color: color, fontWeight: FontWeight.w600, fontSize: big ? 16 : 14)),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// Round frosted icon button (for − / +).
class GlassIconButton extends StatelessWidget {
  const GlassIconButton({super.key, required this.icon, this.onPressed});
  final IconData icon;
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) {
    return ClipOval(
      child: Material(
        color: Colors.white.withValues(alpha: 0.5),
        shape: CircleBorder(side: BorderSide(color: Colors.white.withValues(alpha: 0.95))),
        child: InkWell(
          onTap: onPressed,
          child: Padding(
            padding: const EdgeInsets.all(9),
            child: Icon(icon, color: onPressed == null ? Colors.black26 : kAccent),
          ),
        ),
      ),
    );
  }
}

Future<bool> confirmDelete(BuildContext context, String name) async {
  final r = await showDialog<bool>(
    context: context,
    builder: (c) => AlertDialog(
      title: const Text('Xoá nhắc nhở?'),
      content: Text('Xoá "$name"? Không thể hoàn tác.'),
      actions: [
        TextButton(onPressed: () => Navigator.pop(c, false), child: const Text('Huỷ')),
        TextButton(
          onPressed: () => Navigator.pop(c, true),
          child: const Text('Xoá', style: TextStyle(color: Colors.redAccent, fontWeight: FontWeight.w700)),
        ),
      ],
    ),
  );
  return r ?? false;
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> with WidgetsBindingObserver {
  List<Reminder> _list = [];
  Map<String, bool> _perms = {};
  Timer? _tick;
  bool _tipDone = true;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _reload();
    _tick = Timer.periodic(const Duration(seconds: 1), (_) => setState(() {}));
  }

  @override
  void dispose() {
    _tick?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _reload();
  }

  Future<void> _reload() async {
    final missed = await Native.reschedule(); // also refreshes nextFire after alarms fired
    final list = await ReminderStore.load();
    final perms = await Native.permissions();
    final tipDone = await ReminderStore.tipDone();
    if (!mounted) return;
    setState(() {
      _list = list;
      _perms = perms;
      _tipDone = tipDone;
    });
    if (missed > 0) _showMissed(missed);
  }

  Future<void> _showMissed(int n) => showDialog<void>(
    context: context,
    builder: (c) => AlertDialog(
      icon: const Icon(Icons.alarm_off_rounded, color: Colors.orange, size: 36),
      title: Text('Đã lỡ $n nhắc nhở'),
      content: const Text(
        'Hệ thống đã tắt hẳn app (thường do vuốt bỏ app ở màn đa nhiệm) nên chuông không kêu.\n\n'
        'Để không bị lỡ: mở đa nhiệm → bấm ⋮ trên thẻ Nhắc Nhở → Khoá (Lock), và cho phép app tự khởi chạy.',
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(c), child: const Text('Để sau')),
        TextButton(
          onPressed: () {
            Navigator.pop(c);
            Native.openPermission('autostart');
          },
          child: const Text('Mở cài đặt'),
        ),
      ],
    ),
  );

  /// How to keep the app alive on phones that kill swiped-away apps.
  Widget _keepAliveTip() => Glass(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Row(
          children: [
            Icon(Icons.lock_outline_rounded, color: kAccent),
            SizedBox(width: 8),
            Expanded(child: Text('Giữ nhắc nhở luôn chạy', style: TextStyle(fontWeight: FontWeight.w700, fontSize: 16))),
          ],
        ),
        const SizedBox(height: 6),
        const Text(
          'Trên Realme / OPPO, vuốt bỏ app ở màn đa nhiệm sẽ tắt hẳn app và huỷ mọi lịch nhắc. Hãy:\n'
          '1. Mở đa nhiệm → bấm ⋮ trên thẻ "Nhắc Nhở" → Khoá (Lock).\n'
          '2. Cho phép app tự khởi chạy / chạy nền.',
          style: TextStyle(color: Colors.black54, height: 1.35),
        ),
        const SizedBox(height: 10),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            GlassButton(icon: Icons.settings_rounded, label: 'Tự khởi chạy', onPressed: () => Native.openPermission('autostart')),
            GlassButton(
              icon: Icons.check_rounded,
              label: 'Đã làm',
              tint: const Color(0xFF16A34A),
              onPressed: () async {
                await ReminderStore.setTipDone();
                setState(() => _tipDone = true);
              },
            ),
          ],
        ),
      ],
    ),
  );

  Future<void> _save() async {
    await ReminderStore.save(_list);
    await _reload();
  }

  Future<void> _edit([Reminder? r]) async {
    final result = await Navigator.of(context).push<Object>(
      MaterialPageRoute(builder: (_) => EditorPage(reminder: r?.copy())),
    );
    if (result == EditorPage.deleted && r != null) return _delete(r);
    if (result is! Reminder) return;
    final i = _list.indexWhere((e) => e.id == result.id);
    if (i >= 0) {
      _list[i] = result;
    } else {
      _list.add(result);
    }
    await _save();
  }

  Future<void> _delete(Reminder r) async {
    _list.removeWhere((e) => e.id == r.id);
    if (r.recording.isNotEmpty) File(r.recording).delete().ignore();
    if (r.bgImage.isNotEmpty) File(r.bgImage).delete().ignore();
    await _save();
  }

  Future<void> _toggle(Reminder r, bool on) async {
    r.enabled = on;
    // Turning a finished countdown back on restarts it.
    if (on && r.type == RType.countdown) r.startedAt = DateTime.now().millisecondsSinceEpoch;
    await _save();
  }

  @override
  Widget build(BuildContext context) {
    final missing = <(String, String)>[
      if (_perms['notifications'] == false) ('notifications', 'Bật quyền thông báo'),
      if (_perms['exactAlarm'] == false) ('exactAlarm', 'Cho phép báo thức chính xác'),
      if (_perms['fullScreen'] == false) ('fullScreen', 'Cho phép hiện toàn màn hình khi khoá'),
      if (_perms['battery'] == false) ('battery', 'Bỏ tối ưu pin cho app'),
      if (_perms['overlay'] == false) ('overlay', 'Cho phép hiện trên ứng dụng khác (bật màn hình báo khi đang dùng máy)'),
    ];
    final sorted = [..._list]..sort((a, b) {
      if (a.enabled != b.enabled) return a.enabled ? -1 : 1;
      final an = a.nextFire == 0 ? 1 << 62 : a.nextFire, bn = b.nextFire == 0 ? 1 << 62 : b.nextFire;
      return an.compareTo(bn);
    });
    return Scaffold(
      appBar: AppBar(title: const Text('Nhắc Nhở', style: TextStyle(fontWeight: FontWeight.w800, fontSize: 28))),
      floatingActionButton: GlassButton(
        big: true,
        onPressed: () => _edit(),
        icon: Icons.add_alarm_rounded,
        label: 'Nhắc mới',
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(16, 4, 16, 100),
        children: [
          for (final (key, label) in missing)
            Padding(
              padding: const EdgeInsets.only(bottom: 10),
              child: Glass(
                onTap: () => Native.openPermission(key),
                child: Row(
                  children: [
                    const Icon(Icons.warning_amber_rounded, color: Colors.orange),
                    const SizedBox(width: 10),
                    Expanded(child: Text('$label để nhắc nhở kêu đúng giờ')),
                    const Icon(Icons.chevron_right),
                  ],
                ),
              ),
            ),
          if (!_tipDone) ...[_keepAliveTip(), const SizedBox(height: 12)],
          if (_list.isEmpty)
            const Padding(
              padding: EdgeInsets.only(top: 120),
              child: Text(
                'Chưa có nhắc nhở nào.\nBấm "Nhắc mới" để tạo đếm ngược, lặp lại hoặc theo ngày giờ.',
                textAlign: TextAlign.center,
                style: TextStyle(color: Colors.black54),
              ),
            ),
          for (final r in sorted) ...[_tile(r), const SizedBox(height: 12)],
        ],
      ),
    );
  }

  Widget _tile(Reminder r) {
    final now = DateTime.now().millisecondsSinceEpoch;
    final left = r.enabled && r.nextFire > now ? Duration(milliseconds: r.nextFire - now) : null;
    return Dismissible(
      key: ValueKey(r.id),
      direction: DismissDirection.endToStart,
      confirmDismiss: (_) => confirmDelete(context, r.title.isEmpty ? 'Nhắc nhở' : r.title),
      background: Container(
        alignment: Alignment.centerRight,
        padding: const EdgeInsets.only(right: 24),
        decoration: BoxDecoration(color: Colors.redAccent, borderRadius: BorderRadius.circular(22)),
        child: const Icon(Icons.delete_outline, color: Colors.white),
      ),
      onDismissed: (_) => _delete(r),
      child: Opacity(
        opacity: r.enabled ? 1 : 0.55,
        child: Glass(
          onTap: () => _edit(r),
          child: Row(
            children: [
              Container(
                width: 46,
                height: 46,
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(14),
                  gradient: LinearGradient(colors: [
                    const Color(0xFF34D399).withValues(alpha: 0.35),
                    kAccent.withValues(alpha: 0.35),
                  ]),
                ),
                alignment: Alignment.center,
                child: Text(r.emoji, style: const TextStyle(fontSize: 26)),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      r.title.isEmpty ? 'Nhắc nhở' : r.title,
                      style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700),
                    ),
                    Text(r.describe(), style: const TextStyle(color: Colors.black54, fontSize: 13)),
                    if (left != null)
                      Text(
                        'Còn ${fmtDuration(left)} · ${fmtDateTime(DateTime.fromMillisecondsSinceEpoch(r.nextFire))}',
                        style: const TextStyle(color: kAccent, fontWeight: FontWeight.w700, fontSize: 13,
                            fontFeatures: [FontFeature.tabularFigures()]),
                      )
                    else if (r.enabled)
                      const Text('Đã qua', style: TextStyle(color: Colors.black38, fontSize: 13)),
                    Row(
                      children: [
                        Icon(r.useRecording ? Icons.mic_rounded : Icons.notifications_active_outlined, size: 14, color: Colors.black45),
                        const SizedBox(width: 4),
                        Text(r.useRecording ? 'Ghi âm' : 'Chuông điện thoại', style: const TextStyle(fontSize: 12, color: Colors.black45)),
                      ],
                    ),
                  ],
                ),
              ),
              CupertinoSwitch(value: r.enabled, activeTrackColor: const Color(0xFF34C759), onChanged: (v) => _toggle(r, v)),
            ],
          ),
        ),
      ),
    );
  }
}

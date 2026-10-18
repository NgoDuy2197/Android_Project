import 'dart:io';

import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import 'package:path_provider/path_provider.dart';

import 'models.dart';
import 'native.dart';
import 'ui.dart';

class SettingsPage extends StatefulWidget {
  const SettingsPage({super.key, required this.config});
  final AppConfig config;

  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> with WidgetsBindingObserver {
  AppConfig get config => widget.config;
  Map<String, bool> _perms = {};
  Map<String, dynamic> _ka = {};
  late final _keyCtrl = TextEditingController(text: config.geminiKey);
  bool _showKey = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _loadBackground();
  }

  @override
  void dispose() {
    _keyCtrl.dispose();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _loadBackground();
  }

  Future<void> _loadBackground() async {
    final p = await Native.permissions();
    final ka = await Native.keepAliveStatus();
    if (mounted) {
      setState(() {
        _perms = p;
        _ka = ka;
      });
    }
  }

  /// Everything that keeps the hourly fetch alive on ColorOS & co.
  Widget _backgroundSection(NewsTheme t) {
    final installed = _ka['installed'] == true, running = _ka['running'] == true, permitted = _ka['permitted'] == true;
    final wd = _ka['watchdog'];
    final status = !installed
        ? 'Chưa cài Shizuku — có thể chạy keepalive.bat khi cắm máy tính'
        : !running
        ? 'Shizuku chưa chạy'
        : !permitted
        ? 'Chưa cấp quyền Shizuku cho app'
        : wd == true
        ? 'Watchdog đang chạy — app bị tắt sẽ tự bật lại'
        : 'Watchdog đang tắt';
    Widget tile(IconData icon, String title, bool? ok, VoidCallback onTap, {String? sub}) => ListTile(
      leading: Icon(icon, color: ok == true ? const Color(0xFF16A34A) : t.accent),
      title: Text(title, style: TextStyle(color: t.text)),
      subtitle: sub == null ? null : Text(sub, style: TextStyle(color: t.sub, fontSize: 12)),
      trailing: ok == true ? const Icon(Icons.check_circle, color: Color(0xFF16A34A)) : Icon(Icons.chevron_right, color: t.sub),
      onTap: onTap,
    );
    return Glass(
      theme: t,
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 10, 16, 2),
            child: Text(
              'Realme/OPPO tắt hẳn app khi vuốt bỏ ở đa nhiệm → không quét tin, widget đứng im. '
              'Mở đa nhiệm → ⋮ trên thẻ "Tin Tức" → Khoá (Lock), rồi bật các mục dưới.',
              style: TextStyle(color: t.sub, fontSize: 12, height: 1.35),
            ),
          ),
          tile(Icons.alarm_on_rounded, 'Báo thức chính xác (quét đúng giờ)', _perms['exactAlarm'],
              () => Native.openPermission('exactAlarm')),
          tile(Icons.battery_saver_rounded, 'Bỏ tối ưu pin', _perms['battery'], () => Native.openPermission('battery')),
          tile(Icons.rocket_launch_outlined, 'Cho phép tự khởi chạy', null, () => Native.openPermission('autostart')),
          tile(Icons.shield_moon_outlined, 'Watchdog (Shizuku)', wd == true, () async {
            if (running && !permitted) {
              await Native.shizukuRequest();
            } else if (permitted) {
              wd == true ? await Native.keepAliveStop() : await Native.keepAliveStart();
            }
            await Future.delayed(const Duration(seconds: 1));
            _loadBackground();
          }, sub: status),
        ],
      ),
    );
  }

  Future<void> _save() async {
    await config.save();
    await Native.configChanged();
    if (mounted) setState(() {});
  }

  Future<void> _editFeed([Feed? feed]) async {
    final name = TextEditingController(text: feed?.name ?? '');
    final url = TextEditingController(text: feed?.url ?? 'https://');
    final ok = await showDialog<bool>(
      context: context,
      builder: (c) => AlertDialog(
        title: Text(feed == null ? 'Thêm nguồn RSS' : 'Sửa nguồn RSS'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(controller: name, decoration: const InputDecoration(labelText: 'Tên hiển thị', border: OutlineInputBorder())),
            const SizedBox(height: 12),
            TextField(
              controller: url,
              keyboardType: TextInputType.url,
              decoration: const InputDecoration(labelText: 'Địa chỉ RSS', border: OutlineInputBorder()),
            ),
          ],
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(c, false), child: const Text('Huỷ')),
          FilledButton(onPressed: () => Navigator.pop(c, true), child: const Text('Lưu')),
        ],
      ),
    );
    if (ok != true) return;
    final u = url.text.trim();
    if (Uri.tryParse(u)?.hasScheme != true) {
      if (mounted) toast(context, 'Địa chỉ không hợp lệ');
      return;
    }
    final n = name.text.trim().isEmpty ? Uri.parse(u).host : name.text.trim();
    if (feed == null) {
      config.feeds.add(Feed(id: 'f${DateTime.now().microsecondsSinceEpoch}', name: n, url: u));
    } else {
      feed
        ..name = n
        ..url = u;
    }
    await _save();
    Native.fetchNow();
  }

  Future<void> _pickBackground() async {
    final x = await ImagePicker().pickImage(source: ImageSource.gallery, maxWidth: 2000, maxHeight: 2000);
    if (x == null) return;
    final dir = await getApplicationSupportDirectory();
    final old = config.bgImage;
    final dest = File('${dir.path}/background_${DateTime.now().millisecondsSinceEpoch}.jpg');
    await File(x.path).copy(dest.path);
    config.bgImage = dest.path;
    await _save();
    if (old != null) File(old).delete().ignore();
  }

  @override
  Widget build(BuildContext context) {
    final t = config.palette;
    TextStyle h = TextStyle(color: t.text, fontSize: 13, fontWeight: FontWeight.w700, letterSpacing: 0.4);
    return Scaffold(
      appBar: AppBar(
        title: Text('Cài đặt', style: TextStyle(color: t.text, fontWeight: FontWeight.w700)),
        iconTheme: IconThemeData(color: t.text),
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(16, 4, 16, 40),
        children: [
          Text('NGUỒN RSS', style: h),
          const SizedBox(height: 8),
          Glass(
            theme: t,
            padding: const EdgeInsets.symmetric(vertical: 4),
            child: Column(
              children: [
                for (final f in config.feeds)
                  ListTile(
                    leading: Switch(
                      value: f.enabled,
                      onChanged: (v) {
                        f.enabled = v;
                        _save();
                      },
                    ),
                    title: Text(f.name, style: TextStyle(color: t.text, fontWeight: FontWeight.w600)),
                    subtitle: Text(f.url, maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(color: t.sub, fontSize: 12)),
                    onTap: () => _editFeed(f),
                    trailing: IconButton(
                      icon: const Icon(Icons.delete_outline, color: Colors.redAccent),
                      onPressed: () async {
                        if (await confirm(context, 'Xoá nguồn?', 'Xoá "${f.name}"?')) {
                          config.feeds.remove(f);
                          await _save();
                        }
                      },
                    ),
                  ),
                ListTile(
                  leading: Icon(Icons.add_circle_outline, color: t.accent),
                  title: Text('Thêm nguồn RSS', style: TextStyle(color: t.accent, fontWeight: FontWeight.w600)),
                  onTap: () => _editFeed(),
                ),
                ListTile(
                  leading: Icon(Icons.restore, color: t.sub),
                  title: Text('Khôi phục 2 nguồn mặc định', style: TextStyle(color: t.sub)),
                  onTap: () async {
                    for (final d in defaultFeeds()) {
                      if (!config.feeds.any((f) => f.url == d.url)) config.feeds.add(d);
                    }
                    await _save();
                    Native.fetchNow();
                  },
                ),
              ],
            ),
          ),
          const SizedBox(height: 22),
          Text('GIAO DIỆN', style: h),
          const SizedBox(height: 8),
          SizedBox(
            height: 112,
            child: ListView(
              scrollDirection: Axis.horizontal,
              children: [
                for (final th in NewsTheme.all)
                  GestureDetector(
                    onTap: () {
                      config.theme = th.id;
                      _save();
                    },
                    child: Container(
                      width: 96,
                      margin: const EdgeInsets.only(right: 10),
                      decoration: BoxDecoration(
                        borderRadius: BorderRadius.circular(18),
                        gradient: LinearGradient(colors: th.bg),
                        border: Border.all(color: config.theme == th.id ? th.accent : Colors.white54, width: config.theme == th.id ? 3 : 1),
                      ),
                      child: Column(
                        mainAxisAlignment: MainAxisAlignment.end,
                        children: [
                          Container(
                            height: 22,
                            margin: const EdgeInsets.symmetric(horizontal: 10),
                            decoration: BoxDecoration(color: th.card, borderRadius: BorderRadius.circular(8)),
                          ),
                          const SizedBox(height: 6),
                          Text(
                            th.name,
                            textAlign: TextAlign.center,
                            style: TextStyle(color: th.text, fontSize: 12, fontWeight: FontWeight.w700),
                          ),
                          const SizedBox(height: 10),
                        ],
                      ),
                    ),
                  ),
              ],
            ),
          ),
          const SizedBox(height: 12),
          Glass(
            theme: t,
            padding: const EdgeInsets.symmetric(vertical: 4),
            child: Column(
              children: [
                ListTile(
                  leading: Icon(Icons.wallpaper, color: t.accent),
                  title: Text(config.bgImage == null ? 'Chọn ảnh nền' : 'Đổi ảnh nền', style: TextStyle(color: t.text)),
                  onTap: _pickBackground,
                  trailing: config.bgImage == null
                      ? null
                      : IconButton(
                          tooltip: 'Bỏ ảnh nền',
                          icon: const Icon(Icons.close),
                          onPressed: () {
                            final old = config.bgImage;
                            config.bgImage = null;
                            _save();
                            if (old != null) File(old).delete().ignore();
                          },
                        ),
                ),
                if (config.bgImage != null)
                  Padding(
                    padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
                    child: Row(
                      children: [
                        Text('Làm mờ', style: TextStyle(color: t.text)),
                        Expanded(
                          child: Slider(
                            min: 0,
                            max: 20,
                            value: config.bgBlur,
                            onChanged: (v) => setState(() => config.bgBlur = v),
                            onChangeEnd: (_) => _save(),
                          ),
                        ),
                      ],
                    ),
                  ),
              ],
            ),
          ),
          const SizedBox(height: 22),
          Text('AI TÓM TẮT (GEMINI)', style: h),
          const SizedBox(height: 8),
          Glass(
            theme: t,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                TextField(
                  controller: _keyCtrl,
                  obscureText: !_showKey,
                  autocorrect: false,
                  enableSuggestions: false,
                  style: TextStyle(color: t.text),
                  decoration: InputDecoration(
                    labelText: 'API key Gemini',
                    hintText: 'AIza…',
                    border: const OutlineInputBorder(),
                    suffixIcon: IconButton(
                      icon: Icon(_showKey ? Icons.visibility_off_outlined : Icons.visibility_outlined),
                      onPressed: () => setState(() => _showKey = !_showKey),
                    ),
                  ),
                  onChanged: (v) {
                    config.geminiKey = v.trim();
                    config.save();
                  },
                ),
                const SizedBox(height: 8),
                Text(
                  'Nút ✨ ở trang chính gửi tiêu đề các tin chưa đọc cho gemini-2.5-flash-lite để tóm tắt, '
                  'rồi đánh dấu các tin đó là đã đọc. Lấy key miễn phí tại aistudio.google.com → Get API key. '
                  'Key chỉ lưu trên máy này.',
                  style: TextStyle(color: t.sub, fontSize: 12, height: 1.35),
                ),
              ],
            ),
          ),
          const SizedBox(height: 22),
          Text('CHẠY NỀN', style: h),
          const SizedBox(height: 8),
          _backgroundSection(t),
          const SizedBox(height: 22),
          Text('THÔNG BÁO & CẬP NHẬT', style: h),
          const SizedBox(height: 8),
          Glass(
            theme: t,
            padding: const EdgeInsets.symmetric(vertical: 4),
            child: Column(
              children: [
                SwitchListTile(
                  title: Text('Thông báo khi có tin mới', style: TextStyle(color: t.text)),
                  subtitle: Text('≤ 5 tin: mỗi tin 1 thông báo · > 5 tin: 1 thông báo tổng', style: TextStyle(color: t.sub, fontSize: 12)),
                  value: config.notify,
                  onChanged: (v) {
                    config.notify = v;
                    _save();
                  },
                ),
                ListTile(
                  title: Text('Tự quét tin', style: TextStyle(color: t.text)),
                  trailing: DropdownButton<int>(
                    value: config.intervalMin,
                    underline: const SizedBox(),
                    items: const [
                      DropdownMenuItem(value: 30, child: Text('30 phút')),
                      DropdownMenuItem(value: 60, child: Text('1 giờ')),
                      DropdownMenuItem(value: 120, child: Text('2 giờ')),
                      DropdownMenuItem(value: 360, child: Text('6 giờ')),
                    ],
                    onChanged: (v) {
                      config.intervalMin = v ?? 60;
                      _save();
                    },
                  ),
                ),
                ListTile(
                  leading: Icon(Icons.add_to_home_screen, color: t.accent),
                  title: Text('Thêm widget ra màn hình chính', style: TextStyle(color: t.text)),
                  onTap: () async {
                    if (!await Native.pinWidget() && context.mounted) {
                      toast(context, 'Giữ màn hình chính → Widgets → Tin Tức');
                    }
                  },
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

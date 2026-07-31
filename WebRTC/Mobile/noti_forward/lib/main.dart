import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'app_picker_page.dart';
import 'native_bridge.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const NotiApp());
}

class NotiApp extends StatelessWidget {
  const NotiApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Noti Forward',
      debugShowCheckedModeBanner: false,
      theme: ThemeData.dark(useMaterial3: true).copyWith(
        scaffoldBackgroundColor: const Color(0xFF101216),
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF5865F2),
          brightness: Brightness.dark,
        ),
      ),
      home: const HomePage(),
    );
  }
}

/// Order must match Const.MODE_* on the native side.
enum FwdMode { read, discord, both }

/// Order must match Const.FILTER_* on the native side.
enum FilterMode { all, allow, deny }

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> with WidgetsBindingObserver {
  final _webhookCtrl = TextEditingController();
  final _discordNameCtrl = TextEditingController();
  final _keywordCtrl = TextEditingController();
  final _legacyFilterCtrl = TextEditingController();
  StreamSubscription? _sub;

  bool _granted = false;
  bool _enabled = true;
  FwdMode _mode = FwdMode.read;
  FilterMode _filterMode = FilterMode.all;
  Set<String> _selectedApps = {};
  Map<String, String> _knownLabels = {};

  bool _readContent = true;
  bool _speakAppName = false;
  bool _skipOngoing = true;
  double _rate = 0.5;
  int _minIntervalSec = 0;

  String _ttsLang = '';
  String _ttsVoiceName = '';
  String _ttsVoiceLocale = '';
  List<String> _ttsLangs = [];
  List<({String name, String locale})> _ttsVoices = [];
  bool _loadingTts = false;
  bool _testingWebhook = false;

  bool _keepAlive = true;
  bool _ignoringBattery = false;

  final List<String> _log = [];

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _init();
  }

  Future<void> _init() async {
    final prefs = await SharedPreferences.getInstance();
    _enabled = prefs.getBool('enabled') ?? true;
    _mode = FwdMode.values[(prefs.getInt('mode') ?? 0).clamp(0, 2)];
    _filterMode =
        FilterMode.values[(prefs.getInt('filterMode') ?? 0).clamp(0, 2)];
    _selectedApps = _decodeApps(prefs.getString('selectedApps') ?? '');
    _readContent = prefs.getBool('readContent') ?? true;
    _speakAppName = prefs.getBool('speakAppName') ?? false;
    _skipOngoing = prefs.getBool('skipOngoing') ?? true;
    _rate = (prefs.getInt('rate_pct') ?? 50) / 100.0;
    _minIntervalSec = prefs.getInt('minInterval') ?? 0;
    _webhookCtrl.text = prefs.getString('webhook') ?? '';
    _discordNameCtrl.text = prefs.getString('discordUsername') ?? '';
    _keywordCtrl.text = prefs.getString('keywordFilter') ?? '';
    _legacyFilterCtrl.text = prefs.getString('filter') ?? '';
    _ttsLang = prefs.getString('ttsLang') ?? '';
    _ttsVoiceName = prefs.getString('ttsVoice') ?? '';
    _ttsVoiceLocale = prefs.getString('ttsVoiceLocale') ?? '';
    _keepAlive = prefs.getBool('keepAlive') ?? true;

    // Migrate old free-text fragment filter into allowlist mode once.
    if (_selectedApps.isEmpty &&
        _legacyFilterCtrl.text.trim().isNotEmpty &&
        _filterMode == FilterMode.all) {
      // Keep legacy fragment matching on native side when mode is "all".
    }

    try {
      _sub = NativeBridge.notificationEvents().listen(
        _onNativeEvent,
        onError: (_) {},
      );
    } catch (_) {
      // Event channel unavailable (e.g. widget tests).
    }
    await _refreshPermission();
    await _refreshBattery();
    if (mounted) setState(() {});
    _loadTtsInfo();
    _applyBackground();
  }

  Set<String> _decodeApps(String raw) {
    final s = raw.trim();
    if (s.isEmpty) return {};
    try {
      if (s.startsWith('[')) {
        final list = (jsonDecode(s) as List)
            .map((e) => e.toString().trim())
            .where((e) => e.isNotEmpty);
        return {...list};
      }
    } catch (_) {}
    return {
      for (final p in s.split(','))
        if (p.trim().isNotEmpty) p.trim()
    };
  }

  String _encodeApps(Set<String> apps) =>
      jsonEncode(apps.toList()..sort());

  Future<void> _loadTtsInfo() async {
    setState(() => _loadingTts = true);
    try {
      final r = await NativeBridge.ttsInfo();
      final langs =
          (r?['languages'] as List?)?.map((e) => e.toString()).toList() ?? [];
      final seen = <String>{};
      final voices = ((r?['voices'] as List?) ?? [])
          .map((v) {
            final m = Map<String, dynamic>.from(v as Map);
            return (
              name: (m['name'] ?? '').toString(),
              locale: (m['locale'] ?? '').toString(),
            );
          })
          .where((v) => v.name.isNotEmpty && seen.add(v.name))
          .toList();
      if (!mounted) return;
      setState(() {
        _ttsLangs = langs;
        _ttsVoices = voices;
        _loadingTts = false;
        if (_ttsLang.isNotEmpty && !_ttsLangs.contains(_ttsLang)) {
          _ttsLang = '';
        }
        if (_ttsVoiceName.isNotEmpty &&
            !_ttsVoices.any((v) => v.name == _ttsVoiceName)) {
          _ttsVoiceName = '';
        }
      });
    } catch (_) {
      if (mounted) setState(() => _loadingTts = false);
    }
  }

  Future<void> _speakTest() async {
    await NativeBridge.speakTest(
      text: 'Xin chào, đây là giọng đọc thử của Noti Forward.',
      lang: _ttsLang,
      voice: _ttsVoiceName,
      ratePct: (_rate * 100).round(),
    );
  }

  Future<void> _applyBackground() async {
    await NativeBridge.applyBackground(_enabled && _keepAlive);
  }

  Future<void> _refreshBattery() async {
    final b = await NativeBridge.isIgnoringBattery();
    if (mounted) setState(() => _ignoringBattery = b);
  }

  Future<void> _requestBattery() async {
    await NativeBridge.requestIgnoreBattery();
  }

  Future<void> _testWebhook() async {
    final url = _webhookCtrl.text.trim();
    if (url.isEmpty) {
      _toast('Nhập Discord Webhook URL trước');
      return;
    }
    setState(() => _testingWebhook = true);
    final r = await NativeBridge.testWebhook(
      webhook: url,
      username: _discordNameCtrl.text.trim(),
    );
    if (!mounted) return;
    setState(() => _testingWebhook = false);
    _toast(r.ok ? 'Gửi test Discord thành công' : 'Thất bại: ${r.error}');
  }

  void _toast(String msg) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(msg), behavior: SnackBarBehavior.floating),
    );
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _refreshPermission();
      _refreshBattery();
    }
  }

  Future<void> _save() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('enabled', _enabled);
    await prefs.setInt('mode', _mode.index);
    await prefs.setInt('filterMode', _filterMode.index);
    await prefs.setString('selectedApps', _encodeApps(_selectedApps));
    await prefs.setBool('readContent', _readContent);
    await prefs.setBool('speakAppName', _speakAppName);
    await prefs.setBool('skipOngoing', _skipOngoing);
    await prefs.setInt('rate_pct', (_rate * 100).round());
    await prefs.setInt('minInterval', _minIntervalSec);
    await prefs.setString('webhook', _webhookCtrl.text.trim());
    await prefs.setString('discordUsername', _discordNameCtrl.text.trim());
    await prefs.setString('keywordFilter', _keywordCtrl.text.trim());
    await prefs.setString('filter', _legacyFilterCtrl.text.trim());
    await prefs.setString('ttsLang', _ttsLang);
    await prefs.setString('ttsVoice', _ttsVoiceName);
    await prefs.setString('ttsVoiceLocale', _ttsVoiceLocale);
    await prefs.setBool('keepAlive', _keepAlive);
    await _applyBackground();
  }

  Future<void> _refreshPermission() async {
    final ok = await NativeBridge.isPermissionGranted();
    if (mounted) setState(() => _granted = ok);
  }

  Future<void> _requestPermission() async {
    await NativeBridge.requestPermission();
  }

  Future<void> _openAppPicker() async {
    final result = await Navigator.of(context).push<Set<String>>(
      MaterialPageRoute(
        builder: (_) => AppPickerPage(
          initialSelected: _selectedApps,
          filterModeIndex: _filterMode.index,
        ),
      ),
    );
    if (result == null || !mounted) return;
    setState(() => _selectedApps = result);
    await _save();
  }

  void _onNativeEvent(dynamic event) {
    if (event is! Map) return;
    final pkg = (event['package'] ?? '').toString();
    final title = (event['title'] ?? '').toString();
    final content = (event['content'] ?? '').toString();
    final label = (event['appLabel'] ?? '').toString();
    if (pkg.isNotEmpty && label.isNotEmpty) {
      _knownLabels[pkg] = label;
    }
    if (!mounted) return;
    final name = label.isNotEmpty ? label : pkg;
    setState(() {
      _log.insert(0, '[$name] $title — $content');
      if (_log.length > 80) _log.removeLast();
    });
  }

  int get _setupDone {
    var n = 0;
    if (_granted) n++;
    if (_ignoringBattery) n++;
    if (_keepAlive && _enabled) n++;
    if (_filterMode == FilterMode.all || _selectedApps.isNotEmpty) n++;
    if (_mode == FwdMode.read || _webhookCtrl.text.trim().isNotEmpty) n++;
    return n;
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _sub?.cancel();
    _webhookCtrl.dispose();
    _discordNameCtrl.dispose();
    _keywordCtrl.dispose();
    _legacyFilterCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Noti Forward'),
        actions: [
          Switch(
            value: _enabled,
            onChanged: (v) {
              setState(() => _enabled = v);
              _save();
            },
          ),
          const SizedBox(width: 8),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          _setupChecklist(),
          const SizedBox(height: 16),
          _sectionTitle('Chế độ chuyển tiếp'),
          const SizedBox(height: 8),
          SegmentedButton<FwdMode>(
            segments: const [
              ButtonSegment(
                value: FwdMode.read,
                label: Text('Đọc'),
                icon: Icon(Icons.record_voice_over, size: 18),
              ),
              ButtonSegment(
                value: FwdMode.discord,
                label: Text('Discord'),
                icon: Icon(Icons.send, size: 18),
              ),
              ButtonSegment(
                value: FwdMode.both,
                label: Text('Cả hai'),
                icon: Icon(Icons.merge_type, size: 18),
              ),
            ],
            selected: {_mode},
            onSelectionChanged: (s) {
              setState(() => _mode = s.first);
              _save();
            },
          ),
          const SizedBox(height: 16),
          _appsSection(),
          const SizedBox(height: 16),
          if (_mode != FwdMode.read) ...[
            _discordSection(),
            const SizedBox(height: 16),
          ],
          if (_mode != FwdMode.discord) ...[
            _ttsSection(),
            const SizedBox(height: 16),
          ],
          _filtersSection(),
          const SizedBox(height: 16),
          _backgroundSection(),
          const SizedBox(height: 16),
          _logSection(),
        ],
      ),
    );
  }

  Widget _sectionTitle(String text) => Text(
        text,
        style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 16),
      );

  Widget _setupChecklist() {
    final total = 5;
    final done = _setupDone;
    return Card(
      color: const Color(0xFF171A21),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Expanded(
                  child: Text('Thiết lập nhanh',
                      style: TextStyle(fontWeight: FontWeight.bold)),
                ),
                Text('$done/$total',
                    style: TextStyle(
                      color: done == total
                          ? const Color(0xFF30A46C)
                          : Colors.white70,
                    )),
              ],
            ),
            const SizedBox(height: 8),
            LinearProgressIndicator(
              value: done / total,
              minHeight: 6,
              borderRadius: BorderRadius.circular(4),
            ),
            const SizedBox(height: 12),
            _checkRow(
              ok: _granted,
              title: 'Quyền đọc thông báo',
              actionLabel: _granted ? 'Kiểm tra' : 'Cấp quyền',
              onAction: () async {
                if (_granted) {
                  await _refreshPermission();
                } else {
                  await _requestPermission();
                }
              },
            ),
            _checkRow(
              ok: _ignoringBattery,
              title: 'Tắt tối ưu pin',
              actionLabel: _ignoringBattery ? 'Kiểm tra' : 'Mở',
              onAction: () async {
                if (_ignoringBattery) {
                  await _refreshBattery();
                } else {
                  await _requestBattery();
                  await Future.delayed(const Duration(milliseconds: 500));
                  await _refreshBattery();
                }
              },
            ),
            _checkRow(
              ok: _keepAlive && _enabled,
              title: 'Giữ chạy nền',
              actionLabel: 'Bật',
              onAction: () {
                setState(() {
                  _enabled = true;
                  _keepAlive = true;
                });
                _save();
              },
            ),
            _checkRow(
              ok: _filterMode == FilterMode.all || _selectedApps.isNotEmpty,
              title: _filterMode == FilterMode.all
                  ? 'App filter (đang: tất cả)'
                  : 'Đã chọn ${_selectedApps.length} app',
              actionLabel: 'Chọn app',
              onAction: _openAppPicker,
            ),
            _checkRow(
              ok: _mode == FwdMode.read || _webhookCtrl.text.trim().isNotEmpty,
              title: _mode == FwdMode.read
                  ? 'Webhook (không cần ở chế độ Đọc)'
                  : 'Discord webhook',
              actionLabel: 'Xuống dưới',
              onAction: () {},
            ),
          ],
        ),
      ),
    );
  }

  Widget _checkRow({
    required bool ok,
    required String title,
    required String actionLabel,
    required VoidCallback onAction,
  }) {
    return ListTile(
      dense: true,
      contentPadding: EdgeInsets.zero,
      leading: Icon(
        ok ? Icons.check_circle : Icons.radio_button_unchecked,
        color: ok ? const Color(0xFF30A46C) : Colors.white38,
      ),
      title: Text(title, style: const TextStyle(fontSize: 14)),
      trailing: TextButton(onPressed: onAction, child: Text(actionLabel)),
    );
  }

  Widget _appsSection() {
    final subtitle = switch (_filterMode) {
      FilterMode.all => 'Đang chuyển tiếp tất cả app',
      FilterMode.allow => _selectedApps.isEmpty
          ? 'Chưa chọn app nào — sẽ không forward'
          : 'Chỉ ${_selectedApps.length} app đã chọn',
      FilterMode.deny => _selectedApps.isEmpty
          ? 'Chưa loại trừ app nào — giống tất cả'
          : 'Loại trừ ${_selectedApps.length} app',
    };

    return Card(
      color: const Color(0xFF171A21),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _sectionTitle('App được forward'),
            const SizedBox(height: 4),
            Text(subtitle, style: const TextStyle(color: Colors.white54)),
            const SizedBox(height: 12),
            SegmentedButton<FilterMode>(
              segments: const [
                ButtonSegment(value: FilterMode.all, label: Text('Tất cả')),
                ButtonSegment(value: FilterMode.allow, label: Text('Chỉ chọn')),
                ButtonSegment(value: FilterMode.deny, label: Text('Loại trừ')),
              ],
              selected: {_filterMode},
              onSelectionChanged: (s) {
                setState(() => _filterMode = s.first);
                _save();
              },
            ),
            const SizedBox(height: 12),
            FilledButton.icon(
              onPressed: _openAppPicker,
              icon: const Icon(Icons.apps),
              label: Text(
                _selectedApps.isEmpty
                    ? 'Mở danh sách app'
                    : 'Quản lý ${_selectedApps.length} app đã chọn',
              ),
            ),
            if (_selectedApps.isNotEmpty) ...[
              const SizedBox(height: 10),
              Wrap(
                spacing: 6,
                runSpacing: 6,
                children: _selectedApps.take(12).map((pkg) {
                  final label = _knownLabels[pkg] ?? pkg.split('.').last;
                  return InputChip(
                    label: Text(label, overflow: TextOverflow.ellipsis),
                    onDeleted: () {
                      setState(() => _selectedApps.remove(pkg));
                      _save();
                    },
                  );
                }).toList(),
              ),
              if (_selectedApps.length > 12)
                Padding(
                  padding: const EdgeInsets.only(top: 6),
                  child: Text(
                    '+${_selectedApps.length - 12} app khác',
                    style: const TextStyle(color: Colors.white54, fontSize: 12),
                  ),
                ),
            ],
            if (_filterMode == FilterMode.all) ...[
              const SizedBox(height: 12),
              TextField(
                controller: _legacyFilterCtrl,
                decoration: const InputDecoration(
                  labelText: 'Lọc nhanh theo package (tuỳ chọn)',
                  hintText: 'zalo, messenger, gmail',
                  border: OutlineInputBorder(),
                  helperText:
                      'Để trống = mọi app. Hoặc dùng chế độ Chỉ chọn / Loại trừ ở trên.',
                ),
                onChanged: (_) => _save(),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _discordSection() {
    return Card(
      color: const Color(0xFF171A21),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _sectionTitle('Discord'),
            const SizedBox(height: 12),
            TextField(
              controller: _webhookCtrl,
              keyboardType: TextInputType.url,
              autocorrect: false,
              decoration: const InputDecoration(
                labelText: 'Discord Webhook URL',
                hintText: 'https://discord.com/api/webhooks/…',
                border: OutlineInputBorder(),
              ),
              onChanged: (_) => _save(),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _discordNameCtrl,
              decoration: const InputDecoration(
                labelText: 'Tên hiển thị webhook (tuỳ chọn)',
                hintText: 'Noti Forward',
                border: OutlineInputBorder(),
              ),
              onChanged: (_) => _save(),
            ),
            const SizedBox(height: 12),
            FilledButton.tonalIcon(
              onPressed: _testingWebhook ? null : _testWebhook,
              icon: _testingWebhook
                  ? const SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.science),
              label: Text(_testingWebhook ? 'Đang gửi…' : 'Test webhook'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _ttsSection() {
    return Card(
      color: const Color(0xFF171A21),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _sectionTitle('Đọc to (TTS)'),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('Đọc cả nội dung (không chỉ tiêu đề)'),
              value: _readContent,
              onChanged: (v) {
                setState(() => _readContent = v);
                _save();
              },
            ),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('Đọc kèm tên app'),
              value: _speakAppName,
              onChanged: (v) {
                setState(() => _speakAppName = v);
                _save();
              },
            ),
            Row(
              children: [
                const SizedBox(width: 120, child: Text('Tốc độ đọc')),
                Expanded(
                  child: Slider(
                    value: _rate,
                    min: 0.2,
                    max: 1.0,
                    onChanged: (v) => setState(() => _rate = v),
                    onChangeEnd: (_) => _save(),
                  ),
                ),
              ],
            ),
            if (_loadingTts)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 10),
                child: Row(children: [
                  SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(strokeWidth: 2)),
                  SizedBox(width: 10),
                  Text('Đang dò giọng đọc có trên máy…'),
                ]),
              )
            else ...[
              DropdownButtonFormField<String>(
                initialValue: _ttsLangs.contains(_ttsLang) ? _ttsLang : '',
                isExpanded: true,
                decoration: InputDecoration(
                  labelText: 'Ngôn ngữ đọc',
                  helperText:
                      'Máy có ${_ttsLangs.length} ngôn ngữ. Chọn "vi-VN" để đọc tiếng Việt.',
                  border: const OutlineInputBorder(),
                ),
                items: [
                  const DropdownMenuItem(
                      value: '', child: Text('Tự động (theo máy)')),
                  for (final l in _ttsLangs)
                    DropdownMenuItem(
                      value: l,
                      child: Text(l, overflow: TextOverflow.ellipsis),
                    ),
                ],
                onChanged: (v) {
                  setState(() {
                    _ttsLang = v ?? '';
                    _ttsVoiceName = '';
                    _ttsVoiceLocale = '';
                  });
                  _save();
                },
              ),
              const SizedBox(height: 12),
              DropdownButtonFormField<String>(
                initialValue: _ttsVoices.any((v) => v.name == _ttsVoiceName)
                    ? _ttsVoiceName
                    : '',
                isExpanded: true,
                decoration: InputDecoration(
                  labelText: 'Giọng đọc (tuỳ chọn)',
                  helperText: 'Máy có ${_ttsVoices.length} giọng.',
                  border: const OutlineInputBorder(),
                ),
                items: [
                  const DropdownMenuItem(
                      value: '', child: Text('Tự động (theo ngôn ngữ)')),
                  for (final v in _ttsVoices)
                    DropdownMenuItem(
                      value: v.name,
                      child: Text('${v.locale} — ${v.name}',
                          overflow: TextOverflow.ellipsis),
                    ),
                ],
                onChanged: (v) {
                  setState(() {
                    _ttsVoiceName = v ?? '';
                    final match =
                        _ttsVoices.where((x) => x.name == _ttsVoiceName);
                    _ttsVoiceLocale =
                        match.isNotEmpty ? match.first.locale : '';
                  });
                  _save();
                },
              ),
              const SizedBox(height: 8),
              Row(
                children: [
                  FilledButton.tonalIcon(
                    onPressed: _speakTest,
                    icon: const Icon(Icons.volume_up),
                    label: const Text('Nghe thử'),
                  ),
                  const SizedBox(width: 8),
                  OutlinedButton.icon(
                    onPressed: _loadTtsInfo,
                    icon: const Icon(Icons.refresh),
                    label: const Text('Dò lại'),
                  ),
                ],
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _filtersSection() {
    return Card(
      color: const Color(0xFF171A21),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _sectionTitle('Bộ lọc nâng cao'),
            const SizedBox(height: 12),
            TextField(
              controller: _keywordCtrl,
              decoration: const InputDecoration(
                labelText: 'Từ khóa (tuỳ chọn)',
                hintText: 'otp, mã, ngân hàng',
                border: OutlineInputBorder(),
                helperText:
                    'Chỉ forward khi tiêu đề/nội dung chứa một trong các từ (cách nhau bằng dấu phẩy).',
              ),
              onChanged: (_) => _save(),
            ),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('Bỏ qua thông báo ongoing'),
              subtitle: const Text(
                  'Ẩn thông báo media/đang chạy liên tục (nhạc, navigation…).'),
              value: _skipOngoing,
              onChanged: (v) {
                setState(() => _skipOngoing = v);
                _save();
              },
            ),
            Row(
              children: [
                const SizedBox(
                    width: 160, child: Text('Giãn cách chống lặp (giây)')),
                Expanded(
                  child: Slider(
                    value: _minIntervalSec.toDouble(),
                    min: 0,
                    max: 60,
                    divisions: 12,
                    label: '$_minIntervalSec s',
                    onChanged: (v) =>
                        setState(() => _minIntervalSec = v.round()),
                    onChangeEnd: (_) => _save(),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _backgroundSection() {
    return Card(
      color: const Color(0xFF171A21),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _sectionTitle('Chạy nền & quyền'),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('Giữ chạy nền (thông báo thường trực)'),
              subtitle: const Text(
                  'Giữ ứng dụng sống ở nền để đọc/chuyển tiếp không bị dừng.'),
              value: _keepAlive,
              onChanged: (v) {
                setState(() => _keepAlive = v);
                _save();
              },
            ),
            ListTile(
              contentPadding: EdgeInsets.zero,
              leading: Icon(
                _granted ? Icons.check_circle : Icons.warning,
                color: _granted
                    ? const Color(0xFF30A46C)
                    : const Color(0xFFE5484D),
              ),
              title: Text(_granted
                  ? 'Đã cấp quyền đọc thông báo'
                  : 'Chưa cấp quyền đọc thông báo'),
              subtitle: const Text(
                  'Cần bật "Quyền truy cập thông báo" cho app này'),
              trailing: _granted
                  ? IconButton(
                      icon: const Icon(Icons.refresh),
                      onPressed: _refreshPermission,
                    )
                  : FilledButton(
                      onPressed: _requestPermission,
                      child: const Text('Cấp quyền'),
                    ),
            ),
            ListTile(
              contentPadding: EdgeInsets.zero,
              leading: Icon(
                _ignoringBattery
                    ? Icons.battery_charging_full
                    : Icons.battery_alert,
                color: _ignoringBattery
                    ? const Color(0xFF30A46C)
                    : const Color(0xFFF5A623),
              ),
              title: Text(_ignoringBattery
                  ? 'Đã bỏ tối ưu pin cho app'
                  : 'Nên tắt tối ưu pin cho app'),
              subtitle: const Text(
                  'Nhiều máy (Xiaomi/Oppo/Vivo…) tắt app chạy nền để tiết kiệm pin.'),
              trailing: _ignoringBattery
                  ? IconButton(
                      icon: const Icon(Icons.refresh),
                      onPressed: _refreshBattery,
                    )
                  : FilledButton(
                      onPressed: () async {
                        await _requestBattery();
                        await Future.delayed(const Duration(milliseconds: 500));
                        await _refreshBattery();
                      },
                      child: const Text('Tắt tối ưu'),
                    ),
            ),
            TextButton.icon(
              onPressed: NativeBridge.openAppDetails,
              icon: const Icon(Icons.settings),
              label: const Text('Mở chi tiết app (OEM)'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _logSection() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            _sectionTitle('Nhật ký gần đây'),
            const Spacer(),
            TextButton(
              onPressed: () => setState(() => _log.clear()),
              child: const Text('Xoá'),
            ),
          ],
        ),
        const SizedBox(height: 4),
        const Text(
          'Chạm lâu một dòng để thêm app đó vào danh sách chọn (nếu đã biết package).',
          style: TextStyle(color: Colors.white38, fontSize: 12),
        ),
        const SizedBox(height: 8),
        if (_log.isEmpty)
          const Padding(
            padding: EdgeInsets.all(16),
            child: Text('Chưa có thông báo nào được bắt.',
                style: TextStyle(color: Colors.white54)),
          )
        else
          ..._log.map((l) {
            return Card(
              child: InkWell(
                onLongPress: () => _promptAddFromLog(l),
                child: Padding(
                  padding: const EdgeInsets.all(10),
                  child: Text(l, style: const TextStyle(fontSize: 12.5)),
                ),
              ),
            );
          }),
      ],
    );
  }

  Future<void> _promptAddFromLog(String line) async {
    // Find a known package whose label appears in the log line.
    String? pkg;
    for (final e in _knownLabels.entries) {
      if (line.contains('[${e.value}]') || line.contains(e.key)) {
        pkg = e.key;
        break;
      }
    }
    if (pkg == null) {
      _toast('Không xác định được package từ dòng này');
      return;
    }
    final label = _knownLabels[pkg] ?? pkg;
    final add = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Thêm vào danh sách app?'),
        content: Text('$label\n$pkg'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('Huỷ')),
          FilledButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('Thêm')),
        ],
      ),
    );
    if (add == true && mounted) {
      setState(() {
        _selectedApps.add(pkg!);
        if (_filterMode == FilterMode.all) {
          _filterMode = FilterMode.allow;
        }
      });
      await _save();
      _toast('Đã thêm $label');
    }
  }
}

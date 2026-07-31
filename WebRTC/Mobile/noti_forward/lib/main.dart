import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Channels shared with the native side (see Const.kt).
const _method = MethodChannel('noti_forward/native');
const _events = EventChannel('noti_forward/events');

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
            seedColor: const Color(0xFF5865F2), brightness: Brightness.dark),
      ),
      home: const HomePage(),
    );
  }
}

/// Order must match Const.MODE_* on the native side.
enum FwdMode { read, discord, both }

class HomePage extends StatefulWidget {
  const HomePage({super.key});
  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> with WidgetsBindingObserver {
  final _webhookCtrl = TextEditingController();
  final _filterCtrl = TextEditingController();
  StreamSubscription? _sub;

  bool _granted = false;
  bool _enabled = true;
  FwdMode _mode = FwdMode.read;
  bool _readContent = true;
  double _rate = 0.5;
  int _minIntervalSec = 0;

  // TTS voice config + the device's installed languages/voices.
  String _ttsLang = '';
  String _ttsVoiceName = '';
  String _ttsVoiceLocale = '';
  List<String> _ttsLangs = [];
  List<({String name, String locale})> _ttsVoices = [];
  bool _loadingTts = false;

  // Background reliability.
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
    _mode = FwdMode.values[prefs.getInt('mode') ?? 0];
    _readContent = prefs.getBool('readContent') ?? true;
    _rate = (prefs.getInt('rate_pct') ?? 50) / 100.0;
    _minIntervalSec = prefs.getInt('minInterval') ?? 0;
    _webhookCtrl.text = prefs.getString('webhook') ?? '';
    _filterCtrl.text = prefs.getString('filter') ?? '';
    _ttsLang = prefs.getString('ttsLang') ?? '';
    _ttsVoiceName = prefs.getString('ttsVoice') ?? '';
    _ttsVoiceLocale = prefs.getString('ttsVoiceLocale') ?? '';
    _keepAlive = prefs.getBool('keepAlive') ?? true;

    _sub = _events.receiveBroadcastStream().listen(_onNativeEvent);
    await _refreshPermission();
    await _refreshBattery();
    if (mounted) setState(() {});
    _loadTtsInfo();
    _applyBackground();
  }

  Future<void> _loadTtsInfo() async {
    setState(() => _loadingTts = true);
    try {
      final r = await _method.invokeMapMethod<String, dynamic>('ttsInfo');
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
        if (_ttsLang.isNotEmpty && !_ttsLangs.contains(_ttsLang)) _ttsLang = '';
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
    try {
      await _method.invokeMethod('speakTest', {
        'text': 'Xin chào, đây là giọng đọc thử của Noti Forward.',
        'lang': _ttsLang,
        'voice': _ttsVoiceName,
        'ratePct': (_rate * 100).round(),
      });
    } on PlatformException catch (_) {}
  }

  Future<void> _applyBackground() async {
    try {
      await _method
          .invokeMethod('applyBackground', {'enabled': _enabled && _keepAlive});
    } on PlatformException catch (_) {}
  }

  Future<void> _refreshBattery() async {
    try {
      final b = await _method.invokeMethod<bool>('isIgnoringBattery');
      if (mounted) setState(() => _ignoringBattery = b ?? false);
    } on PlatformException catch (_) {}
  }

  Future<void> _requestBattery() async {
    try {
      await _method.invokeMethod('requestIgnoreBattery');
    } on PlatformException catch (_) {}
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Re-check after the user comes back from the settings screen.
    if (state == AppLifecycleState.resumed) _refreshPermission();
  }

  Future<void> _save() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('enabled', _enabled);
    await prefs.setInt('mode', _mode.index);
    await prefs.setBool('readContent', _readContent);
    await prefs.setInt('rate_pct', (_rate * 100).round());
    await prefs.setInt('minInterval', _minIntervalSec);
    await prefs.setString('webhook', _webhookCtrl.text.trim());
    await prefs.setString('filter', _filterCtrl.text.trim());
    await prefs.setString('ttsLang', _ttsLang);
    await prefs.setString('ttsVoice', _ttsVoiceName);
    await prefs.setString('ttsVoiceLocale', _ttsVoiceLocale);
    await prefs.setBool('keepAlive', _keepAlive);
    await _applyBackground();
  }

  Future<void> _refreshPermission() async {
    try {
      final ok = await _method.invokeMethod<bool>('isPermissionGranted');
      if (mounted) setState(() => _granted = ok ?? false);
    } on PlatformException catch (_) {
      // Leave the last known state.
    }
  }

  Future<void> _requestPermission() async {
    try {
      await _method.invokeMethod('requestPermission');
    } on PlatformException catch (_) {}
    // The listener state updates on resume via didChangeAppLifecycleState.
  }

  void _onNativeEvent(dynamic event) {
    if (event is! Map) return;
    final pkg = (event['package'] ?? '').toString();
    final title = (event['title'] ?? '').toString();
    final content = (event['content'] ?? '').toString();
    if (!mounted) return;
    setState(() {
      _log.insert(0, '[$pkg] $title — $content');
      if (_log.length > 50) _log.removeLast();
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _sub?.cancel();
    _webhookCtrl.dispose();
    _filterCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Noti Forward')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          _permissionCard(),
          const SizedBox(height: 12),
          SwitchListTile(
            title: const Text('Bật chuyển tiếp'),
            value: _enabled,
            onChanged: (v) {
              setState(() => _enabled = v);
              _save();
            },
          ),
          const Divider(),
          const Text('Chế độ', style: TextStyle(fontWeight: FontWeight.bold)),
          const SizedBox(height: 8),
          SegmentedButton<FwdMode>(
            segments: const [
              ButtonSegment(value: FwdMode.read, label: Text('Đọc')),
              ButtonSegment(value: FwdMode.discord, label: Text('Discord')),
              ButtonSegment(value: FwdMode.both, label: Text('Cả hai')),
            ],
            selected: {_mode},
            onSelectionChanged: (s) {
              setState(() => _mode = s.first);
              _save();
            },
          ),
          const SizedBox(height: 12),
          if (_mode != FwdMode.read) ...[
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
          ],
          if (_mode != FwdMode.discord) ...[
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('Đọc cả nội dung (không chỉ tiêu đề)'),
              value: _readContent,
              onChanged: (v) {
                setState(() => _readContent = v);
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
            _ttsPickers(),
          ],
          TextField(
            controller: _filterCtrl,
            decoration: const InputDecoration(
              labelText: 'Lọc app (để trống = tất cả)',
              hintText: 'zalo, messenger, gmail',
              border: OutlineInputBorder(),
              helperText: 'Nhập một phần tên gói, cách nhau bằng dấu phẩy',
            ),
            onChanged: (_) => _save(),
          ),
          const SizedBox(height: 12),
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
                  onChanged: (v) => setState(() => _minIntervalSec = v.round()),
                  onChangeEnd: (_) => _save(),
                ),
              ),
            ],
          ),
          const Divider(),
          _backgroundSection(),
          const Divider(),
          Row(
            children: [
              const Text('Nhật ký gần đây',
                  style: TextStyle(fontWeight: FontWeight.bold)),
              const Spacer(),
              TextButton(
                onPressed: () => setState(() => _log.clear()),
                child: const Text('Xoá'),
              ),
            ],
          ),
          if (_log.isEmpty)
            const Padding(
              padding: EdgeInsets.all(16),
              child: Text('Chưa có thông báo nào được bắt.',
                  style: TextStyle(color: Colors.white54)),
            )
          else
            ..._log.map((l) => Card(
                  child: Padding(
                    padding: const EdgeInsets.all(10),
                    child: Text(l, style: const TextStyle(fontSize: 12.5)),
                  ),
                )),
        ],
      ),
    );
  }

  Widget _ttsPickers() {
    if (_loadingTts) {
      return const Padding(
        padding: EdgeInsets.symmetric(vertical: 10),
        child: Row(children: [
          SizedBox(
              width: 16,
              height: 16,
              child: CircularProgressIndicator(strokeWidth: 2)),
          SizedBox(width: 10),
          Text('Đang dò giọng đọc có trên máy…'),
        ]),
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const SizedBox(height: 4),
        DropdownButtonFormField<String>(
          initialValue: _ttsLangs.contains(_ttsLang) ? _ttsLang : '',
          isExpanded: true,
          decoration: InputDecoration(
            labelText: 'Ngôn ngữ đọc',
            helperText: 'Máy có ${_ttsLangs.length} ngôn ngữ. Chọn "vi-VN" để đọc tiếng Việt.',
            border: const OutlineInputBorder(),
          ),
          items: [
            const DropdownMenuItem(value: '', child: Text('Tự động (theo máy)')),
            for (final l in _ttsLangs)
              DropdownMenuItem(
                  value: l, child: Text(l, overflow: TextOverflow.ellipsis)),
          ],
          onChanged: (v) {
            setState(() {
              _ttsLang = v ?? '';
              // A language choice clears any specific voice so they don't fight.
              _ttsVoiceName = '';
              _ttsVoiceLocale = '';
            });
            _save();
          },
        ),
        const SizedBox(height: 12),
        DropdownButtonFormField<String>(
          initialValue:
              _ttsVoices.any((v) => v.name == _ttsVoiceName) ? _ttsVoiceName : '',
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
              final match = _ttsVoices.where((x) => x.name == _ttsVoiceName);
              _ttsVoiceLocale = match.isNotEmpty ? match.first.locale : '';
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
    );
  }

  Widget _backgroundSection() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text('Chạy nền', style: TextStyle(fontWeight: FontWeight.bold)),
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
        Card(
          color: _ignoringBattery
              ? const Color(0x2230A46C)
              : const Color(0x22F5A623),
          child: ListTile(
            leading: Icon(
                _ignoringBattery
                    ? Icons.battery_charging_full
                    : Icons.battery_alert,
                color: _ignoringBattery
                    ? const Color(0xFF30A46C)
                    : const Color(0xFFF5A623)),
            title: Text(_ignoringBattery
                ? 'Đã bỏ tối ưu pin cho app'
                : 'Nên tắt tối ưu pin cho app'),
            subtitle: const Text(
                'Nhiều máy (Xiaomi/Oppo/Vivo…) tắt app chạy nền để tiết kiệm pin.'),
            trailing: _ignoringBattery
                ? IconButton(
                    icon: const Icon(Icons.refresh),
                    onPressed: _refreshBattery)
                : FilledButton(
                    onPressed: () async {
                      await _requestBattery();
                      await Future.delayed(const Duration(milliseconds: 500));
                      await _refreshBattery();
                    },
                    child: const Text('Tắt tối ưu')),
          ),
        ),
      ],
    );
  }

  Widget _permissionCard() {
    return Card(
      color: _granted ? const Color(0x2230A46C) : const Color(0x22E5484D),
      child: ListTile(
        leading: Icon(_granted ? Icons.check_circle : Icons.warning,
            color:
                _granted ? const Color(0xFF30A46C) : const Color(0xFFE5484D)),
        title: Text(_granted
            ? 'Đã cấp quyền đọc thông báo'
            : 'Chưa cấp quyền đọc thông báo'),
        subtitle:
            const Text('Cần bật "Quyền truy cập thông báo" cho app này'),
        trailing: _granted
            ? IconButton(
                icon: const Icon(Icons.refresh), onPressed: _refreshPermission)
            : FilledButton(
                onPressed: _requestPermission, child: const Text('Cấp quyền')),
      ),
    );
  }
}

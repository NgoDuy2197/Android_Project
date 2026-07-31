import 'package:flutter/material.dart';

import '../config_store.dart';
import '../native_bridge.dart';
import '../theme_controller.dart';

/// Config tab: where captures are saved, motion-detection behaviour, and the
/// Discord webhook. Values are persisted with [ConfigStore] and read directly
/// by the native widget/service, so changes here take effect everywhere.
class ConfigPage extends StatefulWidget {
  const ConfigPage({super.key});

  @override
  State<ConfigPage> createState() => _ConfigPageState();
}

class _ConfigPageState extends State<ConfigPage> {
  final _bridge = NativeBridge.instance;
  final _config = ConfigStore();
  final _webhookCtrl = TextEditingController();
  final _deviceNameCtrl = TextEditingController();

  bool _ready = false;
  String _location = '';
  bool _notiAccess = false;

  @override
  void initState() {
    super.initState();
    _init();
  }

  Future<void> _init() async {
    await _config.load();
    _webhookCtrl.text = _config.webhook;
    // Default the device name to the phone's model on first run, and persist it
    // so the native motion alert uses the same value.
    var name = _config.deviceName;
    if (name.isEmpty) {
      name = await _bridge.deviceName();
      await _config.setDeviceName(name);
    }
    _deviceNameCtrl.text = name;
    final loc = await _bridge.saveLocation();
    final notiAccess = await _bridge.notiAccessGranted();
    if (!mounted) return;
    setState(() {
      _location = loc;
      _notiAccess = notiAccess;
      _ready = true;
    });
  }

  Future<void> _toggleNotiForward(bool value) async {
    await _config.setNotiForwardEnabled(value);
    if (value && !_notiAccess) {
      await _promptNotiAccess();
    }
    if (mounted) setState(() {});
  }

  Future<void> _promptNotiAccess() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('Cần quyền truy cập thông báo'),
        content: const Text(
          'Để chuyển tiếp thông báo, hãy bật "CameraMan" trong màn hình '
          'Notification access (Quyền truy cập thông báo) của hệ thống.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Để sau'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Mở cài đặt'),
          ),
        ],
      ),
    );
    if (ok == true) await _bridge.openNotiAccess();
  }

  Future<void> _refreshNotiAccess() async {
    final granted = await _bridge.notiAccessGranted();
    if (mounted) setState(() => _notiAccess = granted);
  }

  Future<void> _refreshLocation() async {
    final loc = await _bridge.saveLocation();
    if (mounted) setState(() => _location = loc);
  }

  Future<void> _pickFolder() async {
    final uri = await _bridge.pickFolder();
    if (uri == null || uri.isEmpty) return;
    await _config.setSaveTreeUri(uri);
    await _refreshLocation();
    _toast('Đã đổi nơi lưu.');
  }

  Future<void> _useDefaultFolder() async {
    await _config.clearSaveTreeUri();
    await _refreshLocation();
    _toast('Đã chuyển về thư mục mặc định.');
  }

  Future<void> _openFolder() async {
    final status = await _bridge.openFolder();
    if (status.isNotEmpty) _toast(status);
  }

  Future<void> _setMotionEnabled(bool v) async {
    // Persist first so the native service reads the latest config on start.
    await _config.setMotionEnabled(v);
    if (v) {
      await _bridge.startMotion();
    } else {
      await _bridge.stopMotion();
    }
    if (mounted) setState(() {});
  }

  Future<void> _restartMotionIfRunning() async {
    if (!_config.motionEnabled) return;
    // Apply new settings (mode/sensitivity/clip length) to a live session.
    await _bridge.stopMotion();
    await _bridge.startMotion();
  }

  Future<void> _testWebhook() async {
    final url = _webhookCtrl.text.trim();
    if (url.isEmpty) {
      _toast('Hãy nhập webhook trước.', error: true);
      return;
    }
    _toast('Đang gửi thử…');
    final ok = await _bridge.sendTestWebhook(url);
    _toast(ok ? 'Gửi webhook thành công ✓' : 'Gửi webhook thất bại.', error: !ok);
  }

  void _toast(String msg, {bool error = false}) {
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(
        content: Text(msg),
        backgroundColor: error ? const Color(0xFFE5484D) : const Color(0xFF30A46C),
      ));
  }

  @override
  void dispose() {
    _webhookCtrl.dispose();
    _deviceNameCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Cấu hình'), centerTitle: true),
      body: !_ready
          ? const Center(child: CircularProgressIndicator())
          : ListView(
              padding: const EdgeInsets.all(16),
              children: [
                _sectionTitle('Giao diện'),
                Card(
                  child: Padding(
                    padding: const EdgeInsets.all(14),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text('Màu chủ đạo', style: TextStyle(fontSize: 13)),
                        const SizedBox(height: 12),
                        Wrap(
                          spacing: 12,
                          runSpacing: 12,
                          children: [
                            for (final argb in kThemePalette)
                              _colorSwatch(argb),
                          ],
                        ),
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 16),
                _sectionTitle('Thiết bị'),
                Card(
                  child: Padding(
                    padding: const EdgeInsets.all(14),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        TextField(
                          controller: _deviceNameCtrl,
                          decoration: const InputDecoration(
                            labelText: 'Tên thiết bị',
                            prefixIcon: Icon(Icons.badge_outlined),
                            border: OutlineInputBorder(),
                          ),
                          onChanged: (v) => _config.setDeviceName(v),
                        ),
                        const SizedBox(height: 6),
                        const Text(
                          'Tên này hiển thị trong thông báo Discord và là tên mặc định khi làm Client ở tab Từ xa.',
                          style: TextStyle(fontSize: 12, color: Colors.white54),
                        ),
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 16),
                _sectionTitle('Nơi lưu'),
                Card(
                  child: Padding(
                    padding: const EdgeInsets.all(14),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(_location, style: const TextStyle(fontSize: 13)),
                        const SizedBox(height: 12),
                        Row(
                          children: [
                            Expanded(
                              child: OutlinedButton.icon(
                                onPressed: _pickFolder,
                                icon: const Icon(Icons.folder_open),
                                label: const Text('Chọn thư mục'),
                              ),
                            ),
                            const SizedBox(width: 10),
                            Expanded(
                              child: TextButton.icon(
                                onPressed: _useDefaultFolder,
                                icon: const Icon(Icons.restore),
                                label: const Text('Mặc định'),
                              ),
                            ),
                          ],
                        ),
                        const SizedBox(height: 4),
                        SizedBox(
                          width: double.infinity,
                          child: OutlinedButton.icon(
                            onPressed: _openFolder,
                            icon: const Icon(Icons.drive_folder_upload),
                            label: const Text('Mở thư mục chứa ảnh/video'),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 16),

                _sectionTitle('Quay liên tục'),
                Card(
                  child: ListTile(
                    title: const Text('Tự cắt video mỗi'),
                    subtitle: Slider(
                      value: _config.splitMinutes.toDouble(),
                      min: 1,
                      max: 30,
                      divisions: 29,
                      label: '${_config.splitMinutes} phút',
                      onChanged: (v) =>
                          setState(() => _config.setSplitMinutes(v.round())),
                    ),
                    trailing: Text('${_config.splitMinutes}p'),
                  ),
                ),
                const SizedBox(height: 16),

                _sectionTitle('Phát hiện chuyển động'),
                Card(
                  child: Column(
                    children: [
                      SwitchListTile(
                        value: _config.motionEnabled,
                        onChanged: _setMotionEnabled,
                        title: const Text('Bật chế độ phát hiện chuyển động'),
                        subtitle: Text(
                          'Camera ${_config.motionLens == 'front' ? 'trước' : 'sau'} sẽ tự '
                          '${_config.motionMode == 'video' ? 'quay' : 'chụp'} khi thấy chuyển động.',
                        ),
                      ),
                      const Divider(height: 1),
                      ListTile(
                        title: const Text('Khi phát hiện thì'),
                        trailing: SegmentedButton<String>(
                          segments: const [
                            ButtonSegment(value: 'photo', label: Text('Chụp'), icon: Icon(Icons.photo_camera)),
                            ButtonSegment(value: 'video', label: Text('Quay'), icon: Icon(Icons.videocam)),
                          ],
                          selected: {_config.motionMode},
                          onSelectionChanged: (s) async {
                            await _config.setMotionMode(s.first);
                            await _restartMotionIfRunning();
                            setState(() {});
                          },
                        ),
                      ),
                      const Divider(height: 1),
                      ListTile(
                        title: const Text('Dùng camera'),
                        subtitle: Text(
                          'Đang dùng: camera ${_config.motionLens == 'front' ? 'trước' : 'sau'}',
                          style: const TextStyle(fontSize: 12),
                        ),
                        trailing: SegmentedButton<String>(
                          segments: const [
                            ButtonSegment(value: 'back', label: Text('Sau'), icon: Icon(Icons.camera_rear)),
                            ButtonSegment(value: 'front', label: Text('Trước'), icon: Icon(Icons.camera_front)),
                          ],
                          selected: {_config.motionLens},
                          onSelectionChanged: (s) async {
                            await _config.setMotionLens(s.first);
                            await _restartMotionIfRunning();
                            setState(() {});
                          },
                        ),
                      ),
                      const Divider(height: 1),
                      ListTile(
                        title: const Text('Độ nhạy'),
                        subtitle: Slider(
                          value: _config.sensitivityPercent.toDouble(),
                          min: 1,
                          max: 30,
                          divisions: 29,
                          label: '${_config.sensitivityPercent}% khung hình',
                          onChanged: (v) => setState(
                              () => _config.setSensitivityPercent(v.round())),
                          onChangeEnd: (_) => _restartMotionIfRunning(),
                        ),
                        trailing: Text('${_config.sensitivityPercent}%'),
                      ),
                      const Divider(height: 1),
                      ListTile(
                        title: const Text('Thời gian giới hạn mỗi lần'),
                        subtitle: Slider(
                          value: _config.videoSeconds.toDouble().clamp(3, 60),
                          min: 3,
                          max: 60,
                          divisions: 57,
                          label: '${_config.videoSeconds}s',
                          onChanged: (v) =>
                              setState(() => _config.setVideoSeconds(v.round())),
                          onChangeEnd: (_) => _restartMotionIfRunning(),
                        ),
                        trailing: Text('${_config.videoSeconds}s'),
                      ),
                      if (_config.motionMode != 'video')
                        const Padding(
                          padding: EdgeInsets.fromLTRB(16, 0, 16, 10),
                          child: Text(
                            'Giới hạn thời gian áp dụng khi chọn "Quay".',
                            style: TextStyle(fontSize: 11, color: Colors.white38),
                          ),
                        ),
                    ],
                  ),
                ),
                const SizedBox(height: 16),

                _sectionTitle('Thông báo Discord'),
                Card(
                  child: Padding(
                    padding: const EdgeInsets.all(14),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        TextField(
                          controller: _webhookCtrl,
                          keyboardType: TextInputType.url,
                          autocorrect: false,
                          decoration: const InputDecoration(
                            labelText: 'Discord Webhook URL',
                            hintText: 'https://discord.com/api/webhooks/…',
                            prefixIcon: Icon(Icons.link),
                            border: OutlineInputBorder(),
                          ),
                          onChanged: (v) => _config.setWebhook(v),
                        ),
                        const SizedBox(height: 6),
                        const Text(
                          'Khi phát hiện chuyển động, một tin nhắn sẽ được gửi tới webhook này.',
                          style: TextStyle(fontSize: 12, color: Colors.white54),
                        ),
                        const SizedBox(height: 10),
                        Align(
                          alignment: Alignment.centerRight,
                          child: FilledButton.icon(
                            onPressed: _testWebhook,
                            icon: const Icon(Icons.send),
                            label: const Text('Gửi thử'),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 16),

                _sectionTitle('Chuyển tiếp thông báo'),
                Card(
                  child: Column(
                    children: [
                      SwitchListTile(
                        value: _config.notiForwardEnabled,
                        onChanged: _toggleNotiForward,
                        title: const Text('Chuyển tiếp thông báo tới Discord'),
                        subtitle: const Text(
                          'Đọc thông báo hệ thống và gửi tới webhook Discord ở trên. '
                          'Cần cấp "Notification access".',
                        ),
                      ),
                      const Divider(height: 1),
                      ListTile(
                        leading: Icon(
                          _notiAccess ? Icons.check_circle : Icons.error_outline,
                          color: _notiAccess
                              ? const Color(0xFF30A46C)
                              : const Color(0xFFF5A623),
                        ),
                        title: Text(_notiAccess
                            ? 'Đã cấp quyền truy cập thông báo'
                            : 'Chưa cấp quyền truy cập thông báo'),
                        subtitle: const Text('Chạm để mở cài đặt / kiểm tra lại'),
                        trailing: const Icon(Icons.open_in_new),
                        onTap: () async {
                          await _bridge.openNotiAccess();
                          // Re-check when the user returns.
                          await _refreshNotiAccess();
                        },
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 16),

                _sectionTitle('Bảo mật'),
                Card(
                  child: Column(
                    children: [
                      SwitchListTile(
                        value: _config.lockEnabled,
                        onChanged: _toggleLock,
                        title: const Text('Khoá ứng dụng bằng mã PIN'),
                        subtitle: const Text(
                          'Yêu cầu mã PIN khi mở app hoặc sau khi tắt màn hình. '
                          'Việc quay/ghi hình đang chạy vẫn tiếp tục bình thường. '
                          'Bấm widget ngoài màn hình chính không cần PIN.',
                        ),
                      ),
                      if (_config.lockEnabled) ...[
                        const Divider(height: 1),
                        ListTile(
                          leading: const Icon(Icons.password),
                          title: const Text('Đổi mã PIN'),
                          trailing: const Icon(Icons.chevron_right),
                          onTap: _changePassword,
                        ),
                      ],
                    ],
                  ),
                ),
              ],
            ),
    );
  }

  Future<void> _toggleLock(bool value) async {
    if (value) {
      final pw = await _promptNewPassword();
      if (pw == null) return; // cancelled
      await _config.setLockPassword(pw);
      _toast('Đã bật khoá bằng mã PIN.');
    } else {
      final current = await _promptPassword('Nhập mã PIN hiện tại để tắt khoá');
      if (current == null) return;
      if (!_config.verifyPassword(current)) {
        _toast('Mã PIN không đúng.', error: true);
        return;
      }
      await _config.disableLock();
      _toast('Đã tắt khoá.');
    }
    if (mounted) setState(() {});
  }

  Future<void> _changePassword() async {
    final current = await _promptPassword('Nhập mã PIN hiện tại');
    if (current == null) return;
    if (!_config.verifyPassword(current)) {
      _toast('Mã PIN không đúng.', error: true);
      return;
    }
    final pw = await _promptNewPassword();
    if (pw == null) return;
    await _config.setLockPassword(pw);
    _toast('Đã đổi mã PIN.');
  }

  /// Prompts for a new PIN twice (entry + confirm). Returns null if
  /// cancelled or the two entries don't match / are empty.
  Future<String?> _promptNewPassword() async {
    final pw1 = TextEditingController();
    final pw2 = TextEditingController();
    String? errorText;
    final result = await showDialog<String>(
      context: context,
      builder: (context) => StatefulBuilder(
        builder: (context, setDialogState) => AlertDialog(
          title: const Text('Đặt mã PIN'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(
                controller: pw1,
                obscureText: true,
                autofocus: true,
                keyboardType: TextInputType.number,
                decoration: const InputDecoration(labelText: 'Mã PIN mới (chỉ số)'),
              ),
              TextField(
                controller: pw2,
                obscureText: true,
                keyboardType: TextInputType.number,
                decoration: InputDecoration(
                  labelText: 'Nhập lại mã PIN',
                  errorText: errorText,
                ),
              ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context),
              child: const Text('Huỷ'),
            ),
            FilledButton(
              onPressed: () {
                final a = pw1.text;
                final b = pw2.text;
                if (a.length < 4 || !RegExp(r'^\d+$').hasMatch(a)) {
                  setDialogState(() => errorText = 'PIN tối thiểu 4 chữ số');
                } else if (a != b) {
                  setDialogState(() => errorText = 'Không khớp');
                } else {
                  Navigator.pop(context, a);
                }
              },
              child: const Text('Lưu'),
            ),
          ],
        ),
      ),
    );
    pw1.dispose();
    pw2.dispose();
    return result;
  }

  /// Prompts for a single password. Returns null if cancelled.
  Future<String?> _promptPassword(String title) async {
    final ctrl = TextEditingController();
    final result = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(title),
        content: TextField(
          controller: ctrl,
          obscureText: true,
          autofocus: true,
          keyboardType: TextInputType.number,
          decoration: const InputDecoration(labelText: 'Mã PIN'),
          onSubmitted: (v) => Navigator.pop(context, v),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Huỷ'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, ctrl.text),
            child: const Text('OK'),
          ),
        ],
      ),
    );
    ctrl.dispose();
    return result;
  }

  Widget _colorSwatch(int argb) {
    final selected = _config.themeColor == argb;
    final color = Color(argb);
    return GestureDetector(
      onTap: () async {
        await _config.setThemeColor(argb);
        themeColorNotifier.value = color;
        if (mounted) setState(() {});
      },
      child: Container(
        width: 42,
        height: 42,
        decoration: BoxDecoration(
          color: color,
          shape: BoxShape.circle,
          border: Border.all(
            color: selected ? Colors.white : Colors.white24,
            width: selected ? 3 : 1,
          ),
        ),
        child: selected
            ? const Icon(Icons.check, color: Colors.white, size: 22)
            : null,
      ),
    );
  }

  Widget _sectionTitle(String text) => Padding(
        padding: const EdgeInsets.only(bottom: 8, left: 4),
        child: Text(
          text,
          style: const TextStyle(
            fontSize: 15,
            fontWeight: FontWeight.bold,
            color: Color(0xFF30A46C),
          ),
        ),
      );
}

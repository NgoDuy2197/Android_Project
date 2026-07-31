import 'dart:async';

import 'package:flutter/material.dart';
import 'package:mobile_scanner/mobile_scanner.dart';

import 'app_icons.dart';
import 'audio.dart';
import 'button_editor_screen.dart';
import 'config_store.dart';
import 'connection.dart';
import 'foreground.dart';
import 'models.dart';
import 'theme_controller.dart';
import 'widgets.dart';

/// Remoter = client. Owns profiles of soundboard configs, connects to the
/// speaker, sends play commands + live audio, manages buttons and profiles.
class RemoterScreen extends StatefulWidget {
  final ConfigStore store;
  final ThemeController theme;
  const RemoterScreen({super.key, required this.store, required this.theme});

  @override
  State<RemoterScreen> createState() => _RemoterScreenState();
}

class _RemoterScreenState extends State<RemoterScreen> {
  final _conn = WalkieConnection();
  final _engine = AudioEngine();
  final _addressCtrl = TextEditingController();

  ProfilesData _profiles = ProfilesData.initial();
  AppConfig get _config => _profiles.config;

  bool _connected = false;
  bool _connecting = false;
  bool _talking = false;
  bool _remoteTalking = false;

  final Set<String> _speakerHas = {}; // sound ids the speaker already cached
  bool _haveReceived = false;
  Timer? _pushFallback;

  @override
  void initState() {
    super.initState();
    _setup();
  }

  Future<void> _setup() async {
    _profiles = await widget.store.loadProfiles();
    _addressCtrl.text = await widget.store.loadAddress() ?? '';

    _conn.onConnectionChanged = (c) {
      if (!mounted) return;
      setState(() {
        _connected = c;
        _connecting = false;
        if (!c) {
          _remoteTalking = false;
          _talking = false;
        }
      });
      if (c) {
        ForegroundService.start();
        _engine.initLive(offerer: true, sendSignal: _conn.sendSignal);
        // Wait briefly for the speaker's "have" list; if none, send everything.
        _speakerHas.clear();
        _haveReceived = false;
        _pushFallback?.cancel();
        _pushFallback = Timer(const Duration(seconds: 2), () {
          if (!_haveReceived) _pushMissing();
        });
      } else {
        _engine.closeLive();
        _pushFallback?.cancel();
      }
    };
    _conn.onSignal = _engine.handleSignal;
    _conn.onRemoteTalk = (on) {
      if (mounted) setState(() => _remoteTalking = on);
    };
    _conn.onMissingSoundRequest = _pushSound;
    _conn.onHave = (ids) {
      _haveReceived = true;
      _speakerHas
        ..clear()
        ..addAll(ids);
      _pushMissing();
    };

    if (mounted) setState(() {});
  }

  // Send only the sounds the speaker doesn't already have (saves bandwidth).
  Future<void> _pushMissing() async {
    for (final b in _config.buttons) {
      if (b.hasSound && !_speakerHas.contains(b.id)) {
        await _pushSound(b.id);
        _speakerHas.add(b.id);
      }
    }
  }

  Future<void> _pushSound(String id) async {
    final b = _config.buttons.where((e) => e.id == id).firstOrNull;
    if (b == null || !b.hasSound) return;
    final f = await widget.store.soundFile(b.soundFileName!);
    if (await f.exists()) {
      _conn.sendSound(id, 'm4a', await f.readAsBytes());
      _speakerHas.add(id);
    }
  }

  Future<void> _save() => widget.store.saveProfiles(_profiles);

  // --- Connection ------------------------------------------------------------
  Future<void> _connect(String addr) async {
    addr = addr.trim();
    if (addr.isEmpty) return;
    setState(() => _connecting = true);
    _addressCtrl.text = addr;
    await widget.store.saveAddress(addr);
    try {
      await _conn.connect(addr);
    } catch (e) {
      if (mounted) {
        setState(() => _connecting = false);
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('Không kết nối được: $e')));
      }
    }
  }

  Future<void> _scanConnect() async {
    final code = await Navigator.push<String>(
      context,
      MaterialPageRoute(builder: (_) => const _QrScanScreen()),
    );
    if (code != null && code.trim().isNotEmpty) await _connect(code);
  }

  Future<void> _disconnect() async {
    await _conn.close();
    if (mounted) setState(() => _connected = false);
    ForegroundService.stop();
  }

  Future<void> _toggleTalk() async {
    if (!_talking) {
      final ok = await _engine.startTalk();
      if (!ok) {
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
              const SnackBar(content: Text('Cần quyền micro để nói.')));
        }
        return;
      }
      _conn.sendTalk(true);
      setState(() => _talking = true);
    } else {
      await _engine.stopTalk();
      _conn.sendTalk(false);
      setState(() => _talking = false);
    }
  }

  void _pressButton(SoundButton b) {
    if (!_connected) {
      ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Chưa kết nối với Speaker.')));
      return;
    }
    _conn.sendPlay(b.id);
  }

  // --- Profiles --------------------------------------------------------------
  Future<void> _switchProfile(String name) async {
    if (name == _profiles.current) return;
    setState(() => _profiles.current = name);
    await _save();
    if (_connected) _pushMissing();
  }

  Future<void> _addProfile() async {
    final name = await _promptName('Tên profile mới', 'Kịch bản ${_profiles.names.length + 1}');
    if (name == null) return;
    final unique = _uniqueName(name);
    setState(() {
      _profiles.profiles[unique] = AppConfig();
      _profiles.current = unique;
    });
    await _save();
  }

  Future<void> _renameProfile() async {
    final name = await _promptName('Đổi tên profile', _profiles.current);
    if (name == null || name == _profiles.current) return;
    final unique = _uniqueName(name);
    setState(() {
      final cfg = _profiles.profiles.remove(_profiles.current)!;
      _profiles.profiles[unique] = cfg;
      _profiles.current = unique;
    });
    await _save();
  }

  Future<void> _deleteProfile() async {
    if (_profiles.profiles.length <= 1) {
      _toast('Phải còn ít nhất 1 profile.');
      return;
    }
    setState(() {
      _profiles.profiles.remove(_profiles.current);
      _profiles.current = _profiles.profiles.keys.first;
    });
    await _save();
  }

  String _uniqueName(String base) {
    var name = base.trim().isEmpty ? 'Profile' : base.trim();
    var n = name;
    var i = 2;
    while (_profiles.profiles.containsKey(n)) {
      n = '$name ($i)';
      i++;
    }
    return n;
  }

  Future<String?> _promptName(String title, String initial) {
    final ctrl = TextEditingController(text: initial);
    return showDialog<String>(
      context: context,
      builder: (_) => AlertDialog(
        title: Text(title),
        content: TextField(
          controller: ctrl,
          autofocus: true,
          decoration: const InputDecoration(border: OutlineInputBorder()),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(context), child: const Text('Huỷ')),
          FilledButton(
              onPressed: () => Navigator.pop(context, ctrl.text.trim()),
              child: const Text('OK')),
        ],
      ),
    );
  }

  // --- Buttons ---------------------------------------------------------------
  Future<void> _addButton() async {
    final b = SoundButton(
        id: 'b${DateTime.now().microsecondsSinceEpoch}', name: 'Nút mới');
    if (await _openEditor(b) == true) {
      setState(() => _config.buttons.add(b));
      await _save();
      if (_connected) await _pushSound(b.id);
    }
  }

  Future<void> _editButton(SoundButton b) async {
    if (await _openEditor(b) == true) {
      setState(() {});
      await _save();
      // Sound may have been (re)recorded -> resend.
      _speakerHas.remove(b.id);
      if (_connected) await _pushSound(b.id);
    }
  }

  Future<bool?> _openEditor(SoundButton b) {
    return Navigator.push<bool>(
      context,
      MaterialPageRoute(
        builder: (_) =>
            ButtonEditorScreen(store: widget.store, engine: _engine, button: b),
      ),
    );
  }

  Future<void> _deleteButton(SoundButton b) async {
    if (b.hasSound) {
      final f = await widget.store.soundFile(b.soundFileName!);
      if (await f.exists()) {
        try {
          await f.delete();
        } catch (_) {}
      }
    }
    setState(() => _config.buttons.removeWhere((e) => e.id == b.id));
    await _save();
  }

  void _buttonMenu(SoundButton b) {
    showModalBottomSheet(
      context: context,
      builder: (_) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              leading: const Icon(Icons.edit),
              title: const Text('Sửa nút'),
              onTap: () {
                Navigator.pop(context);
                _editButton(b);
              },
            ),
            ListTile(
              leading: const Icon(Icons.delete_outline),
              title: const Text('Xoá nút'),
              onTap: () {
                Navigator.pop(context);
                _deleteButton(b);
              },
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _reorder() async {
    final ordered = await Navigator.push<List<SoundButton>>(
      context,
      MaterialPageRoute(
        builder: (_) => _ReorderScreen(buttons: List.of(_config.buttons)),
      ),
    );
    if (ordered != null) {
      setState(() => _config.buttons = ordered);
      await _save();
    }
  }

  // --- Export / import -------------------------------------------------------
  Future<void> _export() async {
    final path = await widget.store.exportBundle(_config, _profiles.current);
    _toast(path == null ? 'Đã huỷ xuất.' : 'Đã xuất profile "${_profiles.current}".');
  }

  Future<void> _import() async {
    final cfg = await widget.store.importBundle();
    if (cfg == null) return;
    final name = await _promptName(
        'Đặt tên profile nhập vào', 'Nhập ${_profiles.names.length + 1}');
    if (name == null) return;
    final unique = _uniqueName(name);
    setState(() {
      _profiles.profiles[unique] = cfg;
      _profiles.current = unique;
    });
    await _save();
    _toast('Đã nhập profile "$unique".');
    if (_connected) await _pushMissing();
  }

  void _clearRemoteAudio() {
    if (!_connected) {
      _toast('Chưa kết nối Speaker.');
      return;
    }
    _conn.sendClearAudio();
    _speakerHas.clear();
    _toast('Đã yêu cầu xoá audio trên máy Speaker.');
  }

  void _toast(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(msg)));
  }

  @override
  void dispose() {
    _pushFallback?.cancel();
    _addressCtrl.dispose();
    _engine.dispose();
    _conn.close();
    ForegroundService.stop();
    super.dispose();
  }

  // --- UI --------------------------------------------------------------------
  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: _profileSelector(),
        actions: [
          ThemeToggleButton(theme: widget.theme),
          PopupMenuButton<String>(
            onSelected: (v) {
              switch (v) {
                case 'add_profile':
                  _addProfile();
                  break;
                case 'rename_profile':
                  _renameProfile();
                  break;
                case 'delete_profile':
                  _deleteProfile();
                  break;
                case 'reorder':
                  _reorder();
                  break;
                case 'export':
                  _export();
                  break;
                case 'import':
                  _import();
                  break;
                case 'clear_audio':
                  _clearRemoteAudio();
                  break;
              }
            },
            itemBuilder: (_) => const [
              PopupMenuItem(value: 'add_profile', child: Text('➕ Thêm profile')),
              PopupMenuItem(value: 'rename_profile', child: Text('✏️ Đổi tên profile')),
              PopupMenuItem(value: 'delete_profile', child: Text('🗑️ Xoá profile')),
              PopupMenuDivider(),
              PopupMenuItem(value: 'reorder', child: Text('Sắp xếp nút')),
              PopupMenuItem(value: 'export', child: Text('Xuất profile (chọn nơi lưu)')),
              PopupMenuItem(value: 'import', child: Text('Nhập profile (chọn file)')),
              PopupMenuDivider(),
              PopupMenuItem(value: 'clear_audio', child: Text('Xoá audio máy Speaker')),
            ],
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton(
        onPressed: _addButton,
        tooltip: 'Thêm nút',
        child: const Icon(Icons.add),
      ),
      body: SafeArea(
        bottom: false,
        child: Column(
          children: [
            _connectionBar(),
            Expanded(child: _board()),
          ],
        ),
      ),
      // Live-talk button pinned to the bottom of the screen.
      bottomNavigationBar: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(12, 4, 12, 10),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              ReceivingBadge(active: _remoteTalking),
              const SizedBox(height: 4),
              TalkToggle(
                  talking: _talking, enabled: _connected, onPressed: _toggleTalk),
            ],
          ),
        ),
      ),
    );
  }

  Widget _profileSelector() {
    return DropdownButtonHideUnderline(
      child: DropdownButton<String>(
        value: _profiles.current,
        isExpanded: true,
        dropdownColor: Theme.of(context).colorScheme.surfaceContainerHigh,
        icon: const Icon(Icons.arrow_drop_down),
        items: [
          for (final n in _profiles.names)
            DropdownMenuItem(value: n, child: Text(n, overflow: TextOverflow.ellipsis)),
        ],
        onChanged: (v) {
          if (v != null) _switchProfile(v);
        },
      ),
    );
  }

  Widget _connectionBar() {
    final cs = Theme.of(context).colorScheme;
    if (_connected) {
      return Container(
        width: double.infinity,
        color: cs.primaryContainer,
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
        child: Row(
          children: [
            Icon(Icons.link, color: cs.onPrimaryContainer),
            const SizedBox(width: 8),
            Expanded(
              child: Text('Đã kết nối với Speaker',
                  style: TextStyle(
                      color: cs.onPrimaryContainer,
                      fontWeight: FontWeight.w600)),
            ),
            TextButton(onPressed: _disconnect, child: const Text('Ngắt')),
          ],
        ),
      );
    }
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 10, 12, 4),
      child: Column(
        children: [
          // Primary: scan a QR then connect immediately.
          SizedBox(
            width: double.infinity,
            child: FilledButton.icon(
              onPressed: _connecting ? null : _scanConnect,
              icon: _connecting
                  ? const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(strokeWidth: 2))
                  : const Icon(Icons.qr_code_scanner),
              label: Text(_connecting ? 'Đang kết nối…' : 'Kết nối (quét QR)'),
              style: FilledButton.styleFrom(
                  padding: const EdgeInsets.symmetric(vertical: 14)),
            ),
          ),
          const SizedBox(height: 6),
          // Secondary: manual IP entry.
          Row(
            children: [
              Expanded(
                child: TextField(
                  controller: _addressCtrl,
                  keyboardType: TextInputType.url,
                  autocorrect: false,
                  decoration: const InputDecoration(
                    isDense: true,
                    labelText: 'hoặc nhập IP:PORT',
                    hintText: '192.168.1.10:8787',
                    border: OutlineInputBorder(),
                  ),
                  onSubmitted: _connect,
                ),
              ),
              const SizedBox(width: 8),
              OutlinedButton(
                onPressed: _connecting ? null : () => _connect(_addressCtrl.text),
                child: const Text('Nối'),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _board() {
    if (_config.buttons.isEmpty) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Text(
            'Profile "${_profiles.current}" chưa có nút.\nBấm + để thêm nút (tên, icon, ghi âm).',
            textAlign: TextAlign.center,
            style: TextStyle(color: Theme.of(context).hintColor),
          ),
        ),
      );
    }
    return GridView.builder(
      padding: const EdgeInsets.all(12),
      gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
        crossAxisCount: 3,
        mainAxisSpacing: 12,
        crossAxisSpacing: 12,
        childAspectRatio: 0.95,
      ),
      itemCount: _config.buttons.length,
      itemBuilder: (context, i) {
        final b = _config.buttons[i];
        return _SoundButtonTile(
          button: b,
          onTap: () => _pressButton(b),
          onLongPress: () => _buttonMenu(b),
        );
      },
    );
  }
}

/// One tappable soundboard button: icon on top, name below.
class _SoundButtonTile extends StatelessWidget {
  final SoundButton button;
  final VoidCallback onTap;
  final VoidCallback onLongPress;
  const _SoundButtonTile({
    required this.button,
    required this.onTap,
    required this.onLongPress,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Material(
      color: cs.surfaceContainerHighest,
      borderRadius: BorderRadius.circular(16),
      child: InkWell(
        borderRadius: BorderRadius.circular(16),
        onTap: onTap,
        onLongPress: onLongPress,
        child: Padding(
          padding: const EdgeInsets.all(8),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(iconAt(button.iconIndex), size: 40, color: cs.primary),
              const SizedBox(height: 8),
              Text(
                button.name,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                textAlign: TextAlign.center,
                style: const TextStyle(fontWeight: FontWeight.w600),
              ),
              if (!button.hasSound)
                Padding(
                  padding: const EdgeInsets.only(top: 2),
                  child: Text('chưa ghi âm',
                      style: TextStyle(
                          fontSize: 10, color: Theme.of(context).hintColor)),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Drag-to-reorder screen. Returns the reordered list.
class _ReorderScreen extends StatefulWidget {
  final List<SoundButton> buttons;
  const _ReorderScreen({required this.buttons});

  @override
  State<_ReorderScreen> createState() => _ReorderScreenState();
}

class _ReorderScreenState extends State<_ReorderScreen> {
  late List<SoundButton> _items;

  @override
  void initState() {
    super.initState();
    _items = List.of(widget.buttons);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Sắp xếp nút'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, _items),
            child: const Text('Xong'),
          ),
        ],
      ),
      body: ReorderableListView.builder(
        itemCount: _items.length,
        onReorder: (oldIndex, newIndex) {
          setState(() {
            if (newIndex > oldIndex) newIndex -= 1;
            final item = _items.removeAt(oldIndex);
            _items.insert(newIndex, item);
          });
        },
        itemBuilder: (context, i) {
          final b = _items[i];
          return ListTile(
            key: ValueKey(b.id),
            leading: Icon(iconAt(b.iconIndex)),
            title: Text(b.name),
            trailing: const Icon(Icons.drag_handle),
          );
        },
      ),
    );
  }
}

/// Full-screen QR scanner; pops with the decoded string.
class _QrScanScreen extends StatefulWidget {
  const _QrScanScreen();

  @override
  State<_QrScanScreen> createState() => _QrScanScreenState();
}

class _QrScanScreenState extends State<_QrScanScreen> {
  final _controller = MobileScannerController();
  bool _handled = false;

  void _onDetect(BarcodeCapture capture) {
    if (_handled) return;
    final value =
        capture.barcodes.isNotEmpty ? capture.barcodes.first.rawValue : null;
    if (value == null || value.trim().isEmpty) return;
    _handled = true;
    Navigator.pop(context, value);
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Quét mã QR')),
      body: Stack(
        alignment: Alignment.center,
        children: [
          MobileScanner(controller: _controller, onDetect: _onDetect),
          Container(
            width: 240,
            height: 240,
            decoration: BoxDecoration(
              border: Border.all(color: Colors.white, width: 3),
              borderRadius: BorderRadius.circular(16),
            ),
          ),
        ],
      ),
    );
  }
}

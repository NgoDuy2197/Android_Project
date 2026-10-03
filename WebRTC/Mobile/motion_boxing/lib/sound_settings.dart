import 'dart:convert';
import 'dart:io';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/foundation.dart';
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Every moment in the game that makes a sound. Each one can be configured
/// (bundled default / off / custom file) from the sound settings screen.
enum SfxEvent {
  roundStart('round_start', '🔔', 'Vào trận', 'Chuông khi bắt đầu trận',
      'bell_start.wav', false),
  hit('hit', '👊', 'Đấm trúng', 'Mỗi cú đấm trúng đối thủ', 'hit.wav', true),
  miss('miss', '💨', 'Đấm trượt', 'Cú đấm hụt / đấm khi đang bị tấn công',
      'miss.wav', true, 0.85),
  combo('combo', '🔥', 'Combo', 'Khi đạt combo x3, x5, x10…', 'combo.wav',
      true, 0.8),
  alert('alert', '⚠️', 'Đối thủ tấn công', 'Cảnh báo: xoay cổ tay để gạt đỡ',
      'alert.wav', true),
  block('block', '🛡️', 'Gạt đỡ thành công', 'Đỡ được đòn của đối thủ',
      'block.wav', true),
  hurt('hurt', '🤕', 'Bị trúng đòn', 'Khi bạn bị đối thủ đấm trúng',
      'hurt.wav', true),
  lowHp('low_hp', '❤️', 'Sắp hết máu', 'Khi máu của bạn xuống dưới 30%',
      'low_hp.wav', false, 0.9),
  knockout('knockout', '💥', 'Hạ gục (KO)', 'Tiếng đổ gục của đối thủ',
      'knockout.wav', false),
  roundEnd('round_end', '🔕', 'Hết hiệp', 'Chuông kết thúc hiệp sau KO',
      'bell_end.wav', false),
  victory('victory', '🏆', 'Chiến thắng', 'Nhạc chiến thắng sau khi hạ gục',
      'victory.wav', false),
  newRound('new_round', '🥊', 'Hiệp mới', 'Bắt đầu hiệp tiếp theo',
      'new_round.wav', false),
  gameOver('game_over', '😵', 'Thua trận', 'Khi bạn hết máu', 'gameover.wav',
      false),
  tap('tap', '👆', 'Chạm nút', 'Bấm nút trong menu', 'tap.wav', true, 0.6);

  const SfxEvent(this.key, this.emoji, this.label, this.description,
      this.asset, this.fast,
      [this.defaultVolume = 1.0]);

  /// Stable id used in preferences and custom file names.
  final String key;
  final String emoji;
  final String label;
  final String description;

  /// Bundled default file under assets/sounds/.
  final String asset;

  /// Short, rapidly repeated sound -> low-latency pool.
  final bool fast;
  final double defaultVolume;
}

enum SoundMode { standard, off, custom }

class SoundConfig {
  SoundMode mode;
  String? path; // absolute path of the copied custom file
  String? name; // original file name, for display
  double volume; // 0..1

  SoundConfig(
      {this.mode = SoundMode.standard,
      this.path,
      this.name,
      this.volume = 1.0});

  Map<String, dynamic> toJson() =>
      {'m': mode.name, 'p': path, 'n': name, 'v': volume};

  static SoundConfig fromJson(Map<String, dynamic> j, double defVol) {
    final m = SoundMode.values.firstWhere((e) => e.name == j['m'],
        orElse: () => SoundMode.standard);
    final v = j['v'];
    return SoundConfig(
      mode: m,
      path: j['p'] as String?,
      name: j['n'] as String?,
      volume: (v is num ? v.toDouble() : defVol).clamp(0.0, 1.0),
    );
  }
}

/// Persistent sound configuration (shared_preferences).
class SoundSettings extends ChangeNotifier {
  static const _kMaster = 'sfx.master';
  static const _kMuted = 'sfx.muted';
  static String _kEvent(SfxEvent e) => 'sfx.event.${e.key}';

  double masterVolume = 1.0;
  bool muted = false;
  final Map<SfxEvent, SoundConfig> _cfg = {
    for (final e in SfxEvent.values) e: SoundConfig(volume: e.defaultVolume)
  };

  SharedPreferences? _prefs;

  SoundConfig of(SfxEvent e) => _cfg[e]!;

  Future<void> load() async {
    try {
      final p = _prefs ??= await SharedPreferences.getInstance();
      masterVolume = (p.getDouble(_kMaster) ?? 1.0).clamp(0.0, 1.0);
      muted = p.getBool(_kMuted) ?? false;
      for (final e in SfxEvent.values) {
        final raw = p.getString(_kEvent(e));
        if (raw == null) continue;
        try {
          _cfg[e] = SoundConfig.fromJson(
              jsonDecode(raw) as Map<String, dynamic>, e.defaultVolume);
        } catch (_) {
          // Corrupt entry -> keep the default.
        }
      }
    } catch (_) {
      // Preferences unavailable -> run with defaults.
    }
    notifyListeners();
  }

  Future<void> _saveEvent(SfxEvent e) async {
    try {
      final p = _prefs ??= await SharedPreferences.getInstance();
      await p.setString(_kEvent(e), jsonEncode(_cfg[e]!.toJson()));
    } catch (_) {}
  }

  Future<void> setMasterVolume(double v) async {
    masterVolume = v.clamp(0.0, 1.0);
    notifyListeners();
    try {
      final p = _prefs ??= await SharedPreferences.getInstance();
      await p.setDouble(_kMaster, masterVolume);
    } catch (_) {}
  }

  Future<void> setMuted(bool m) async {
    muted = m;
    notifyListeners();
    try {
      final p = _prefs ??= await SharedPreferences.getInstance();
      await p.setBool(_kMuted, muted);
    } catch (_) {}
  }

  Future<void> setMode(SfxEvent e, SoundMode m) async {
    _cfg[e]!.mode = m;
    notifyListeners();
    await _saveEvent(e);
  }

  Future<void> setVolume(SfxEvent e, double v) async {
    _cfg[e]!.volume = v.clamp(0.0, 1.0);
    notifyListeners();
    await _saveEvent(e);
  }

  /// Lets the user pick an audio file, copies it into the app documents dir
  /// (so it survives the picker cache being cleared) and selects it.
  /// Returns false if cancelled or the copy failed.
  Future<bool> pickCustom(SfxEvent e) async {
    try {
      final res = await FilePicker.platform.pickFiles(type: FileType.audio);
      final src = res?.files.single.path;
      if (src == null) return false;
      final name = res!.files.single.name;
      final dir = await _customDir();
      final dot = name.lastIndexOf('.');
      final ext = dot >= 0 ? name.substring(dot) : '';
      final dest =
          '${dir.path}/${e.key}_${DateTime.now().millisecondsSinceEpoch}$ext';
      await File(src).copy(dest);
      final old = _cfg[e]!.path;
      _cfg[e]!
        ..mode = SoundMode.custom
        ..path = dest
        ..name = name;
      notifyListeners();
      await _saveEvent(e);
      if (old != null && old != dest) _deleteQuietly(old);
      return true;
    } catch (_) {
      return false;
    }
  }

  /// Restores every event to its bundled sound and removes copied files.
  Future<void> resetAll() async {
    for (final e in SfxEvent.values) {
      final old = _cfg[e]!.path;
      if (old != null) _deleteQuietly(old);
      _cfg[e] = SoundConfig(volume: e.defaultVolume);
      await _saveEvent(e);
    }
    await setMuted(false);
    await setMasterVolume(1.0);
  }

  Future<Directory> _customDir() async {
    final docs = await getApplicationDocumentsDirectory();
    final d = Directory('${docs.path}/custom_sounds');
    if (!await d.exists()) await d.create(recursive: true);
    return d;
  }

  void _deleteQuietly(String path) {
    File(path).delete().catchError((_) => File(path));
  }
}

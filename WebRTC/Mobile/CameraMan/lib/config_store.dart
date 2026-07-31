import 'dart:convert';

import 'package:crypto/crypto.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Persists the user's settings via `shared_preferences`.
///
/// The keys and value encodings MUST match the native `Const`/`AppConfig`
/// classes, because the widget and the background service read the same
/// preference file directly. In particular, numeric values are stored as
/// `int` (not `double`) so the native side can read them back reliably.
class ConfigStore {
  // Key names — keep in sync with Const.kt.
  static const _kMotionEnabled = 'motion_enabled';
  static const _kMotionMode = 'motion_mode'; // "photo" | "video"
  static const _kWebhook = 'discord_webhook';
  static const _kSaveTreeUri = 'save_tree_uri';
  static const _kSensitivity = 'motion_sensitivity'; // int percent 1..30
  static const _kVideoSeconds = 'motion_video_seconds'; // int seconds
  static const _kMotionLens = 'motion_lens'; // "front" | "back"
  static const _kRemoteName = 'remote_client_name'; // display name as a client
  static const _kRemoteServer = 'remote_server_address'; // last server address

  late SharedPreferences _prefs;

  Future<void> load() async {
    _prefs = await SharedPreferences.getInstance();
  }

  bool get motionEnabled => _prefs.getBool(_kMotionEnabled) ?? false;
  Future<void> setMotionEnabled(bool v) => _prefs.setBool(_kMotionEnabled, v);

  String get motionMode => _prefs.getString(_kMotionMode) ?? 'photo';
  Future<void> setMotionMode(String v) => _prefs.setString(_kMotionMode, v);

  String get webhook => _prefs.getString(_kWebhook) ?? '';
  Future<void> setWebhook(String v) => _prefs.setString(_kWebhook, v.trim());

  // Forward system notifications to the Discord webhook.
  static const _kNotiForward = 'noti_forward_enabled';
  bool get notiForwardEnabled => _prefs.getBool(_kNotiForward) ?? false;
  Future<void> setNotiForwardEnabled(bool v) =>
      _prefs.setBool(_kNotiForward, v);

  String get saveTreeUri => _prefs.getString(_kSaveTreeUri) ?? '';
  Future<void> setSaveTreeUri(String v) => _prefs.setString(_kSaveTreeUri, v);
  Future<void> clearSaveTreeUri() => _prefs.remove(_kSaveTreeUri);

  /// 1..30 (%). Lower = more sensitive (fires on smaller movements).
  int get sensitivityPercent => _prefs.getInt(_kSensitivity) ?? 6;
  Future<void> setSensitivityPercent(int v) =>
      _prefs.setInt(_kSensitivity, v);

  int get videoSeconds => _prefs.getInt(_kVideoSeconds) ?? 10;
  Future<void> setVideoSeconds(int v) => _prefs.setInt(_kVideoSeconds, v);

  /// Which camera motion detection uses: "front" | "back" (default back).
  String get motionLens => _prefs.getString(_kMotionLens) ?? 'back';
  Future<void> setMotionLens(String v) => _prefs.setString(_kMotionLens, v);

  /// Auto-split a continuous manual recording every N minutes (default 5).
  static const _kSplitMinutes = 'manual_split_minutes';
  int get splitMinutes => _prefs.getInt(_kSplitMinutes) ?? 5;
  Future<void> setSplitMinutes(int v) => _prefs.setInt(_kSplitMinutes, v);

  // Device name (default = phone model), shown in Discord alerts and used as
  // the default name in the remote-control tab.
  static const _kDeviceName = 'device_name';
  String get deviceName => _prefs.getString(_kDeviceName) ?? '';
  Future<void> setDeviceName(String v) =>
      _prefs.setString(_kDeviceName, v.trim());

  // Remote-control tab.
  String get remoteName => _prefs.getString(_kRemoteName) ?? '';
  Future<void> setRemoteName(String v) => _prefs.setString(_kRemoteName, v.trim());

  String get remoteServer => _prefs.getString(_kRemoteServer) ?? '';
  Future<void> setRemoteServer(String v) =>
      _prefs.setString(_kRemoteServer, v.trim());

  // Theme accent (seed) color, stored as an ARGB int.
  static const _kThemeColor = 'theme_color';
  static const defaultThemeColor = 0xFF30A46C;

  int get themeColor => _prefs.getInt(_kThemeColor) ?? defaultThemeColor;
  Future<void> setThemeColor(int argb) => _prefs.setInt(_kThemeColor, argb);

  // App lock (password required to use the UI).
  static const _kLockEnabled = 'lock_enabled';
  static const _kLockHash = 'lock_hash';

  bool get lockEnabled => _prefs.getBool(_kLockEnabled) ?? false;
  String get _lockHash => _prefs.getString(_kLockHash) ?? '';

  String _hash(String pw) => sha256.convert(utf8.encode(pw)).toString();

  Future<void> setLockPassword(String pw) async {
    await _prefs.setString(_kLockHash, _hash(pw));
    await _prefs.setBool(_kLockEnabled, true);
  }

  Future<void> disableLock() async {
    await _prefs.setBool(_kLockEnabled, false);
    await _prefs.remove(_kLockHash);
  }

  /// True when [pw] matches the stored password hash.
  bool verifyPassword(String pw) =>
      _lockHash.isNotEmpty && _hash(pw) == _lockHash;
}

import 'package:shared_preferences/shared_preferences.dart';

/// Lưu cài đặt của người dùng qua `shared_preferences`.
///
/// - [clientName]  : tên hiển thị của máy này khi đóng vai client. Server chỉ
///   thấy tên này *sau khi* client đã kết nối (gửi trong gói `hello`).
/// - [serverAddress]: địa chỉ "IP:PORT" của server gần nhất, để lần sau điền sẵn.
class ConfigStore {
  static const _kClientName = 'client_name';
  static const _kServerAddress = 'server_address';
  static const _kSaveTreeUri = 'server_save_tree_uri';
  static const _kQuality = 'server_stream_quality';
  static const _kAudioMic = 'server_audio_mic';

  late SharedPreferences _prefs;

  Future<void> load() async {
    _prefs = await SharedPreferences.getInstance();
  }

  String get clientName => _prefs.getString(_kClientName) ?? '';
  Future<void> setClientName(String v) =>
      _prefs.setString(_kClientName, v.trim());

  String get serverAddress => _prefs.getString(_kServerAddress) ?? '';
  Future<void> setServerAddress(String v) =>
      _prefs.setString(_kServerAddress, v.trim());

  /// Thư mục (SAF tree uri) mà Server lưu ảnh nhận được. Rỗng = lưu vào thư
  /// viện ảnh của hệ thống (mặc định).
  String get serverSaveTreeUri => _prefs.getString(_kSaveTreeUri) ?? '';
  Future<void> setServerSaveTreeUri(String v) =>
      _prefs.setString(_kSaveTreeUri, v);
  Future<void> clearServerSaveTreeUri() => _prefs.remove(_kSaveTreeUri);

  /// Chế độ đường truyền khi Server xem trực tiếp: `sharp` (ưu tiên hình ảnh
  /// sắc nét) hoặc `speed` (ưu tiên tốc độ / độ trễ thấp).
  String get streamQuality => _prefs.getString(_kQuality) ?? 'sharp';
  Future<void> setStreamQuality(String v) => _prefs.setString(_kQuality, v);

  /// Server yêu cầu client thu âm bằng micro thay vì âm thanh hệ thống (cho
  /// game/app chặn thu âm).
  bool get audioFromMic => _prefs.getBool(_kAudioMic) ?? false;
  Future<void> setAudioFromMic(bool v) => _prefs.setBool(_kAudioMic, v);
}

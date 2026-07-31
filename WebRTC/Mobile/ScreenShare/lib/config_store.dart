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
}

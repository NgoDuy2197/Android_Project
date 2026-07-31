import 'package:flutter/services.dart';

/// Kết quả lưu ảnh ở phía Server.
class SaveResult {
  SaveResult(this.ok, this.location);

  final bool ok;

  /// Mô tả nơi ảnh được lưu (đường dẫn / tên thư mục / "thư viện ảnh").
  final String location;
}

/// Cầu nối tới phần native Android qua MethodChannel `screenshare/native`.
///
/// Chức năng duy nhất của phần native là chạy một *foreground service* kiểu
/// `mediaProjection`. Android 14+ yêu cầu service này phải đang chạy TRƯỚC khi
/// bắt đầu quay màn hình, nếu không hệ thống sẽ ném lỗi và làm app dừng đột ngột.
/// Vì vậy client luôn gọi [startProjectionService] ngay trước khi getDisplayMedia.
class NativeBridge {
  NativeBridge._();
  static final NativeBridge instance = NativeBridge._();

  static const _method = MethodChannel('screenshare/native');

  /// Bật foreground service (kiểu mediaProjection, kèm camera nếu có thể) để
  /// giữ tiến trình sống và cho phép quay màn hình khi app ở nền.
  /// Bọc lỗi để không bao giờ làm app crash nếu thiếu phần native.
  Future<void> startProjectionService() async {
    try {
      await _method.invokeMethod('startService');
    } on PlatformException {
      // Không phải Android / service không sẵn sàng — vẫn thử chạy ở tiền cảnh.
    } on MissingPluginException {
      // Nền tảng không có phần native — bỏ qua.
    }
  }

  Future<void> stopProjectionService() async {
    try {
      await _method.invokeMethod('stopService');
    } catch (_) {}
  }

  /// Tên thiết bị (vd "Samsung SM-G991B") dùng làm tên client mặc định.
  Future<String> deviceName() async {
    try {
      final name = await _method.invokeMethod<String>('deviceName');
      return (name == null || name.trim().isEmpty)
          ? 'Thiết bị Android'
          : name.trim();
    } catch (_) {
      return 'Thiết bị Android';
    }
  }

  // --- Lưu ảnh ở phía Server ----------------------------------------------

  /// Mở trình chọn thư mục của hệ thống (SAF). Trả về tree uri đã chọn, hoặc
  /// null nếu người dùng huỷ / không mở được.
  Future<String?> pickFolder() async {
    try {
      return await _method.invokeMethod<String>('pickFolder');
    } catch (_) {
      return null;
    }
  }

  /// Lưu [bytes] (ảnh nhận được) vào [treeUri] nếu có; ngược lại lưu vào thư
  /// viện ảnh của hệ thống. [suggestedName] là phần tên gợi ý (không có đuôi).
  Future<SaveResult> saveImage(
    Uint8List bytes, {
    required String treeUri,
    required String suggestedName,
  }) async {
    try {
      final loc = await _method.invokeMethod<String>('saveImage', {
        'bytes': bytes,
        'treeUri': treeUri,
        'name': suggestedName,
      });
      return SaveResult(loc != null, loc ?? '');
    } catch (e) {
      return SaveResult(false, '$e');
    }
  }

  /// Mô tả nơi lưu hiện tại để hiển thị trong cài đặt.
  Future<String> saveLocationLabel(String treeUri) async {
    try {
      final label =
          await _method.invokeMethod<String>('saveLocationLabel', {
        'treeUri': treeUri,
      });
      return label ?? 'Thư viện ảnh của máy';
    } catch (_) {
      return treeUri.isEmpty ? 'Thư viện ảnh của máy' : treeUri;
    }
  }
}

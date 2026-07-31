# CameraMan

Ứng dụng Flutter (Android) chụp ảnh / quay video nhanh:

- **Widget màn hình chính** với **4 nút**: Chụp ảnh trước, Quay trước, Chụp ảnh sau, Quay sau. Bấm là chụp/quay ngay, không cần mở app. Nút "Quay" bấm lần nữa để dừng.
- **Giao diện trong app** có đúng 4 chức năng đó ở tab **Chụp/Quay**.
- Tab **Thư viện**: xem lại ảnh/video đã chụp, xoá (giữ để xoá).
- Tab **Từ xa** (điều khiển thiết bị khác qua Wi-Fi cùng mạng LAN):
  - Nút **Server**: bật web server local + hiện **QR**; xem danh sách máy đã kết nối, bấm vào 1 máy để ra lệnh (chụp / quay / ghi âm / đổi camera trước–sau / lấy nét) và **xem trực tiếp** camera của máy đó (WebRTC).
  - Nút **Client**: máy này bị điều khiển; có thể **đặt tên** (mặc định lấy tên máy), quét QR hoặc nhập địa chỉ để kết nối. Ảnh/video/ghi âm được lưu **trên máy Client**.
- Tab **Cấu hình**:
  - Chọn **nơi lưu** (nếu chưa chọn thì lưu mặc định vào bộ nhớ riêng của app — luôn có quyền ghi).
  - Bật/tắt chế độ **tự ghi khi phát hiện chuyển động**, chọn **chụp** hay **quay**, chỉnh độ nhạy và độ dài clip.
  - Nhập **Discord webhook**: khi phát hiện chuyển động sẽ gửi tin nhắn tới đó (có nút "Gửi thử").

## Build APK

Chạy `build_apk.bat` (cần cài Flutter SDK và có `flutter` trong PATH). File APK nằm ở
`build/app/outputs/flutter-apk/app-release.apk`.

Chạy thử trên máy đang cắm USB: `run_app.bat`.

## Thêm widget ra màn hình chính

Sau khi cài: giữ vùng trống màn hình chính → **Widgets** → tìm **CameraMan** → kéo widget 4 nút ra.

## Kiến trúc

Việc chụp/quay/phát hiện chuyển động chạy **native (CameraX)** trong một foreground
service, nên hoạt động cả khi tắt màn hình / app chạy nền, và cả khi bấm từ widget.

| Thành phần | Vai trò |
|---|---|
| `lib/` | Giao diện Flutter: tab chụp, thư viện, cấu hình; cầu nối MethodChannel/EventChannel. |
| `MainActivity.kt` | Cầu nối Dart ↔ native, chọn thư mục (SAF), test webhook. |
| `CameraCaptureService.kt` | Chụp ảnh / quay video headless + phát hiện chuyển động bằng CameraX. |
| `CameraManWidgetProvider.kt` | Widget 4 nút; chuyển thao tác thành lệnh cho service. |
| `MediaStorage.kt` | Quyết định nơi lưu (thư mục app mặc định hoặc thư mục SAF người dùng chọn) và liệt kê media. |
| `MotionDetector.kt` | So sánh khung hình để phát hiện chuyển động. |
| `DiscordNotifier.kt` | Gửi tin nhắn tới Discord webhook. |

Cấu hình lưu bằng `shared_preferences`; native đọc chung tệp preference đó để widget
và service hoạt động đúng mà không cần Dart đang chạy.

## Ghi chú / giới hạn

- Cần cấp quyền **Camera** và **Micro** (cho video có tiếng); Android 13+ cần quyền
  **Thông báo** để hiển thị thông báo foreground service.
- Nơi lưu mặc định: `Android/data/<package>/files/DCIM/CameraMan`. Ảnh/video ở đây
  xem/ phát lại trực tiếp trong tab Thư viện. Nếu chọn thư mục khác qua SAF, các mục
  đó sẽ mở bằng ứng dụng xem ngoài của hệ thống.
- Phát hiện chuyển động dùng **camera sau**.
- Chưa chạy build/kiểm thử trên thiết bị trong repo này — hãy chạy `build_apk.bat`
  hoặc `run_app.bat` để build và thử thực tế.

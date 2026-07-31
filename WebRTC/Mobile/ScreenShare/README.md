# ScreenShare

Chia sẻ **toàn bộ màn hình** giữa hai (hoặc nhiều) máy Android trong cùng mạng
Wi-Fi/LAN, qua WebRTC. Cùng một app cài trên mọi máy; mỗi máy tự chọn vai trò.

- **Server (máy nhận):** hiện mã QR chứa địa chỉ của mình, liệt kê các máy đã
  kết nối, bấm vào một máy để xem màn hình trực tiếp, kích hoạt chia sẻ từ xa,
  hoặc yêu cầu chụp ảnh camera trước/sau.
- **Client (máy chia sẻ):** quét QR để kết nối, đặt tên hiển thị, bấm nút để
  chia sẻ toàn bộ màn hình. Cũng nhận lệnh chụp ảnh trước/sau rồi gửi về Server.

Không cần máy chủ ngoài — chính máy Server chạy WebSocket signaling trên thiết bị.

## Cài đặt nhanh

1. Chạy `build_apk.bat` để tạo `app-release.apk`.
2. Copy APK sang **cả hai** điện thoại và cài (bật "Cài từ nguồn không xác định").
3. Hai máy phải cùng mạng Wi-Fi/LAN.

Hoặc chạy trực tiếp qua USB: `run_app.bat`.

## Cách dùng

1. Máy A: mở app → **Server**. Màn hình hiện mã QR và địa chỉ `IP:PORT`.
2. Máy B: mở app → **Client** → đặt tên → **Quét QR** (hoặc nhập địa chỉ) → **Kết nối**.
3. Máy B bấm **Bắt đầu chia sẻ màn hình** → chấp nhận hộp thoại xin phép của
   hệ thống. (Máy A cũng có thể bấm **Kích hoạt chia sẻ** để nhắc máy B.)
4. Máy A: bấm vào máy B trong danh sách → **Xem màn hình** để xem trực tiếp,
   hoặc **Chụp ảnh trước/sau** để lấy ảnh camera của máy B.

## Ghi chú kỹ thuật & giới hạn

- **Quyền quay màn hình:** Android bắt buộc hiện hộp thoại cho phép mỗi lần bắt
  đầu quay — không thể bỏ qua bằng phần mềm. Lệnh "Kích hoạt chia sẻ" từ Server
  chỉ *nhắc* máy Client; người dùng Client vẫn phải bấm "Cho phép".
- **App/nội dung không cho chia sẻ:** một số nội dung được bảo vệ (DRM, màn
  hình nhập mật khẩu, ngân hàng…) sẽ hiện **màn hình đen** thay vì nội dung.
  App không crash trong trường hợp này; khi luồng bị hệ thống thu hồi, Client
  tự **thử chia sẻ lại** vài lần rồi tạm nghỉ nếu vẫn không được.
- **Foreground service** kiểu `mediaProjection` được bật trước khi quay (yêu cầu
  của Android 14+); nhờ đó việc chia sẻ vẫn chạy khi Client chuyển sang app khác.
- **Ảnh camera** được gửi qua WebSocket dưới dạng base64; Server hiển thị (bấm
  ảnh để phóng to) và **tự động lưu** mỗi ảnh nhận được. Nơi lưu cấu hình được
  ở thẻ "Nơi lưu ảnh nhận được" trên màn hình Server:
  - **Mặc định:** thư viện ảnh của máy (Android 10+ lưu vào `Pictures/ScreenShare`,
    hiện ngay trong ứng dụng Ảnh).
  - **Chọn thư mục:** bấm "Chọn thư mục" để chọn thư mục bất kỳ qua trình chọn
    của hệ thống (Storage Access Framework) — ghi trực tiếp, không cần quyền lưu trữ.
- Chỉ dùng trong mạng nội bộ tin cậy: kết nối là cleartext (ws\://), không mã hoá.

## Cấu trúc mã nguồn

```
lib/
  main.dart                 Khởi tạo app, xin quyền, mở HomePage
  config_store.dart         Lưu tên client + địa chỉ server gần nhất
  native_bridge.dart        Gọi foreground service (mediaProjection) + tên máy
  remote/
    screen_server.dart      Vai trò Server: HTTP+WS, người xem WebRTC, nhận ảnh
    screen_client.dart      Vai trò Client: chia sẻ màn hình, chụp ảnh, tự thử lại
  pages/
    home_page.dart          Chọn vai trò + giao diện Server/Client + xem ảnh + quét QR
android/app/src/main/kotlin/com/example/screen_share/
    MainActivity.kt         MethodChannel "screenshare/native"
    ScreenShareService.kt   Foreground service kiểu mediaProjection|camera
```

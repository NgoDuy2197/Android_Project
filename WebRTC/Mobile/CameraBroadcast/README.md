# CameraBroadcast

Bản prototype WebRTC: phát camera + mic của điện thoại lên một signaling server qua WebRTC
(quét QR hoặc nhập địa chỉ server). Đây là "hạt giống" ban đầu của loạt thử nghiệm WebRTC
(ScreenShare, CameraMan…).

## Tính năng
- Kết nối tới signaling server (QR / nhập tay).
- Stream camera + microphone tới server.

## Stack & quyền
- Flutter client + server Node.js (thư mục con có `node_modules`).
- Quyền: `CAMERA`, `RECORD_AUDIO`, foreground service `camera|microphone`.

## Trạng thái
Prototype (chỉ 2 file dart + 2 kt). Dùng để tham khảo/nền tảng; nếu phát triển tiếp nên
thêm xác thực cho signaling và cân nhắc gộp với ScreenShare/CameraMan.

## Build
```bat
build_apk.bat     :: hoặc: build_all.bat CameraBroadcast
```

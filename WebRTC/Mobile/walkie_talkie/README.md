# Walkie Talkie

Bộ đàm push-to-talk (PTT) qua mạng LAN/WebRTC giữa các thiết bị.

## Tính năng
- Hai vai trò: **Speaker** và **Remoter**.
- Nút bấm-để-nói tuỳ chỉnh (trình chỉnh nút).
- Truyền âm thanh qua foreground service (chạy nền khi tắt màn hình).

## Stack & quyền
- Flutter + native Kotlin.
- Quyền: `RECORD_AUDIO`, `CAMERA`, foreground service `microphone|mediaPlayback`.

## Build
```bat
build_apk.bat     :: hoặc: build_all.bat walkie_talkie
```

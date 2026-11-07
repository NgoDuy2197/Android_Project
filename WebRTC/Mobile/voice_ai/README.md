# Voice AI

Trợ lý giọng nói: nói → chuyển thành văn bản (speech-to-text) → gửi AI → đọc kết quả (TTS).

## Tính năng
- Nhận giọng nói bằng SpeechRecognizer của Android.
- Gọi AI client, trả lời bằng giọng nói (TTS).
- Hỗ trợ Android TV (điều hướng D-pad), QR, nhật ký trong app, chia sẻ.

## Stack & quyền
- Flutter + native Kotlin (SpeechRecognizer).
- Quyền: `RECORD_AUDIO`, `CAMERA`, foreground service `microphone|mediaPlayback|specialUse`.

## Build
```bat
build_apk.bat     :: hoặc: build_all.bat voice_ai
```

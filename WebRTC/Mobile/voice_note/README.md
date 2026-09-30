# VoiceNote — ghi âm micro → text, chạy ngầm

Nhận dạng giọng nói **offline** bằng [Vosk](https://alphacephei.com/vosk/) (mặc định model tiếng Việt `vosk-model-small-vn-0.4`, ~32 MB, tự tải lần đầu).

## Định dạng file

Mỗi ngày một file `transcript_YYYY-MM-DD.txt` (UTF-8). Mỗi quãng nghỉ → một đoạn mới:

```
08:15:32:
- Xin chào mọi người hôm nay

08:15:40:
- Câu tiếp theo
```

Hai kết quả cách nhau < `pause_ms` (mặc định 1200 ms) được gộp vào cùng một đoạn; đặt `0` để mọi quãng nghỉ đều xuống đoạn mới.

## Android (`android/`)

- Build: `android\build.bat` → `android\VoiceNote.apk` (`build.bat install` để cài qua adb).
- Kotlin thuần, không AndroidX; chạy dưới dạng foreground service (micro), có nút Dừng trong thông báo.
- Tick **"Tự chạy khi bật máy"** để khởi động cùng máy. Bấm **"Bỏ tối ưu pin"** để chạy ngầm ổn định hơn.
- File lưu tại `Android/data/com.dsoft.voicenote/files/transcripts/` (xem qua USB hoặc nút "Chia sẻ hôm nay").
- Cấu hình ngay trong app: Model URL, định dạng giờ (`SimpleDateFormat`, vd `HH:mm:ss`), `pause_ms`.

## Windows (`windows/`)

- Build: `windows\build_exe.bat` → `windows\VoiceNote.exe` (một file, chạy ở khay hệ thống, không cửa sổ console).
- Chạy thử từ source: `python voicenote.py --console`.
- Menu khay: Tạm dừng/Tiếp tục, Mở file hôm nay, Mở thư mục, Cấu hình, **Chạy khi khởi động Windows** (ghi vào `HKCU\...\Run`), Mở log, Thoát.
- Cấu hình: `%APPDATA%\VoiceNote\config.json` — sửa xong sẽ tự áp dụng sau ~2 s.

| Khoá | Mặc định | Ý nghĩa |
|---|---|---|
| `model_url` | model tiếng Việt nhỏ | Model Vosk dạng .zip, tải một lần vào `%APPDATA%\VoiceNote\models` |
| `model_dir` | `""` | Đường dẫn model đã giải nén sẵn (bỏ qua bước tải) |
| `output_dir` | `Documents\VoiceNote` | Thư mục lưu transcript |
| `time_format` | `%H:%M:%S` | Định dạng giờ theo `strftime` |
| `pause_ms` | `1200` | Ngưỡng gộp đoạn |
| `input_device` | `null` | Micro mặc định, hoặc tên/chỉ số thiết bị |
| `autostart` | `false` | Chạy khi khởi động Windows |
| `start_paused` | `false` | Mở lên ở trạng thái tạm dừng |

## Chịu lỗi

- Mọi lỗi (mất micro, micro bị chiếm, model hỏng, lỗi ghi đĩa) được bắt, hiện trạng thái, rồi tự thử lại (backoff 2 s → 60 s).
- Mỗi đoạn được ghi và `fsync` ngay → crash chỉ mất đoạn đang nói dở.
- Model hỏng → tự tải lại. Rút/cắm lại micro (Windows) → tự dò thiết bị lại.
- Android: `START_STICKY` + tự khôi phục sau khi cập nhật app; nếu hệ điều hành chặn bật micro từ nền, app hiện thông báo "Chạm để bắt đầu ghi âm".

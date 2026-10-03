# VoiceNote — ghi âm micro → text, chạy ngầm

Nhận dạng giọng nói **offline**. Windows mặc định dùng **Whisper** (faster-whisper, model `small`, chính xác, có dấu câu; tự tải ~480 MB lần đầu), tuỳ chọn Vosk (nhẹ). Android mặc định dùng **SpeechRecognizer của máy** (Google, online, giống voice_ai), tuỳ chọn Vosk offline.

Windows cắt câu bằng **Silero VAD** (mạng nơ-ron phân biệt giọng người với tiếng ồn): tiếng quạt, gõ phím, ồn nền không bị tính là đang nói, nên quãng nghỉ luôn được nhận ra và Whisper chỉ phải xử lý đoạn có tiếng nói. Chọn ngôn ngữ **Tiếng Việt** (mặc định) hoặc **English**; model tự tải lần đầu, mỗi ngôn ngữ một thư mục riêng.

Model Vosk (dùng khi `engine = vosk`, và trên Android):

| Ngôn ngữ | Android | Windows |
|---|---|---|
| Tiếng Việt | `vosk-model-vn-0.4` (74 MB) | `vosk-model-vn-0.4` (74 MB) |
| English | `vosk-model-small-en-us-0.15` (41 MB) | `vosk-model-en-us-0.22-lgraph` (130 MB) |

**Lọc nhiễu:** câu có độ tin cậy trung bình < `min_confidence` (mặc định 0.6) bị bỏ; câu chỉ 1–2 từ cần thêm +0.25. Tăng nếu còn bắt tiếng ồn, giảm nếu bị mất câu thật.

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
- Cấu hình ngay trong app: ngôn ngữ, Model URL (theo ngôn ngữ), định dạng giờ (`SimpleDateFormat`, vd `HH:mm:ss`), `pause_ms`, lọc nhiễu.
- Màn hình chính có thanh đo độ to micro, chữ đang nói (màu xanh) và nội dung đã ghi hôm nay.
- Engine **Google** cần mạng; mất mạng thì tự thử lại (0.8 s → 15 s). Engine **Vosk** chạy offline nhưng tiếng Việt kém.

## Windows (`windows/`)

- Build: `windows\build_exe.bat` → `windows\VoiceNote.exe` (một file, chạy ở khay hệ thống, không cửa sổ console).
- Chạy thử từ source: `python voicenote.py --console` (hoặc `--show` để mở luôn cửa sổ, `--lang en`).
- **Cửa sổ nội dung** (click icon khay hoặc menu "Hiện cửa sổ nội dung"): chọn ngôn ngữ, Tạm dừng, **thanh đo độ to micro**, xem nội dung đã ghi hôm nay cập nhật trực tiếp + chữ đang nói. Đóng cửa sổ chỉ ẩn đi, app vẫn chạy.
- Menu khay: Hiện cửa sổ, Tạm dừng/Tiếp tục, **Ngôn ngữ**, Mở file hôm nay, Mở thư mục, Cấu hình, **Chạy khi khởi động Windows** (ghi vào `HKCU\...\Run`), Mở log, Thoát.
- Cấu hình: `%APPDATA%\VoiceNote\config.json` — sửa xong sẽ tự áp dụng sau ~2 s.

| Khoá | Mặc định | Ý nghĩa |
|---|---|---|
| `language` | `vi` | `vi` hoặc `en` |
| `engine` | `whisper` | `whisper` (chính xác, ~500 MB RAM) hoặc `vosk` (nhẹ, tiếng Việt kém) |
| `whisper_model` | `small` | `tiny` / `base` (nhẹ hơn, kém hơn) / `small` / `medium` / `large-v3-turbo` (chính xác hơn, chậm hơn) |
| `whisper_threads` | `0` | Số luồng CPU cho Whisper, 0 = một nửa số nhân |
| `model_url_vi` / `model_url_en` | xem bảng trên | Model Vosk dạng .zip, tải một lần vào `%APPDATA%\VoiceNote\models` |
| `model_dir_vi` / `model_dir_en` | `""` | Đường dẫn model đã giải nén sẵn (bỏ qua bước tải) |
| `min_db` | `-50` | Tiếng nhỏ hơn mức này (dBFS) luôn coi là im lặng. Chỉnh ngay trong cửa sổ; vạch xanh trên thanh đo là ngưỡng — đặt cao hơn mức ồn nền lúc không nói vài dB |
| `batch_ms` | `3000` | Whisper: khi nói liền không nghỉ, cứ mỗi N ms dịch luôn một đoạn (chữ hiện dần), hết câu mới ghi vào file. `0` = chỉ dịch khi nghỉ |
| `min_confidence` | `0.6` | Ngưỡng lọc nhiễu của Vosk (0–1) |
| `output_dir` | `Documents\VoiceNote` | Thư mục lưu transcript |
| `time_format` | `%H:%M:%S` | Định dạng giờ theo `strftime` |
| `pause_ms` | `800` | Whisper: im lặng bao lâu thì kết thúc một đoạn. Vosk: ngưỡng gộp đoạn |
| `input_device` | `null` | Micro mặc định, hoặc tên/chỉ số thiết bị |
| `autostart` | `false` | Chạy khi khởi động Windows |
| `start_paused` | `false` | Mở lên ở trạng thái tạm dừng |
| `show_window_on_start` | `false` | Mở cửa sổ nội dung khi khởi động |

## Chịu lỗi

- Mọi lỗi (mất micro, micro bị chiếm, model hỏng, lỗi ghi đĩa) được bắt, hiện trạng thái, rồi tự thử lại (backoff 2 s → 60 s).
- Mỗi đoạn được ghi và `fsync` ngay → crash chỉ mất đoạn đang nói dở.
- Model hỏng → tự tải lại. Rút/cắm lại micro (Windows) → tự dò thiết bị lại.
- Android: `START_STICKY` + tự khôi phục sau khi cập nhật app; nếu hệ điều hành chặn bật micro từ nền, app hiện thông báo "Chạm để bắt đầu ghi âm".

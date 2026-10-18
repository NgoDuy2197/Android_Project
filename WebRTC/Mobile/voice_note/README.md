# VoiceNote — ghi âm micro → text, chạy ngầm

Nhận dạng giọng nói **offline**. Windows mặc định dùng **Whisper** (faster-whisper, model `small`, chính xác, có dấu câu; tự tải ~480 MB lần đầu), tuỳ chọn Vosk (nhẹ). Android dùng **SpeechRecognizer của máy** (Google, online).

Windows cắt câu bằng **Silero VAD** (mạng nơ-ron phân biệt giọng người với tiếng ồn): tiếng quạt, gõ phím, ồn nền không bị tính là đang nói, nên quãng nghỉ luôn được nhận ra và Whisper chỉ phải xử lý đoạn có tiếng nói. Chọn ngôn ngữ **Tiếng Việt** (mặc định) hoặc **English**; model tự tải lần đầu, mỗi ngôn ngữ một thư mục riêng.

Model Vosk trên Windows (dùng khi `engine = vosk`):

| Ngôn ngữ | Windows |
|---|---|
| Tiếng Việt | `vosk-model-vn-0.4` (74 MB) |
| English | `vosk-model-en-us-0.22-lgraph` (130 MB) |

**Lọc nhiễu:** câu có độ tin cậy trung bình < `min_confidence` (mặc định 0.6) bị bỏ; câu chỉ 1–2 từ cần thêm +0.25. Tăng nếu còn bắt tiếng ồn, giảm nếu bị mất câu thật.

## Định dạng file (Windows)

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
- Kotlin thuần, chạy dưới dạng foreground service (micro) + wake lock, nên **vẫn ghi khi tắt màn hình**. Thông báo có nút Dừng.
- Engine: chỉ **Google** (cần mạng; mất mạng tự thử lại 0.8 s → 15 s). Ngôn ngữ ghi: **Tự động, Tiếng Việt, English, 日本語, 中文**.
  - **Tự động**: nhờ engine tự phát hiện/chuyển ngôn ngữ (phụ thuộc máy và Android; không phải máy nào cũng hỗ trợ — máy không hỗ trợ sẽ dùng ngôn ngữ mặc định của hệ thống).
- **Dịch** (Google Dịch qua mạng, cổng web miễn phí nên có thể bị giới hạn nếu dùng quá nhiều):
  - Mỗi đoạn có nút **Dịch** (tự nhận ngôn ngữ gốc → dịch sang ngôn ngữ mục tiêu, mặc định Tiếng Việt, đổi trong Cài đặt › Dịch). Bản dịch hiện ngay dưới câu gốc.
  - Khi xem một phiên, nút **Dịch tất cả** (biểu tượng 文A) mở hộp chọn **ngôn ngữ gốc** (gồm *Tự động*) và **ngôn ngữ đích**, rồi dịch toàn bộ.
- **Ngắt dòng theo độ yên lặng:** khi âm thanh nhỏ hơn *mức yên lặng* liên tục bằng *thời gian chờ nghe* (mặc định 1 giây), câu đang nói được chốt thành một dòng chat. Chỉ ngắt **một lần**: im lặng lâu cũng không sinh thêm dòng trống, dòng mới chỉ bắt đầu khi có tiếng nói mới.
  - Giá trị mặc định đặt trong Cài đặt › Ngắt dòng.
  - Trong phiên có hai thanh trượt chỉnh trực tiếp (chỉ áp dụng cho phiên đó). Thanh đo âm lượng có vạch đứt nét đánh dấu mức yên lặng.
- Phiên ghi hiện dạng **chat**: mỗi dòng đã chốt là một bong bóng, dòng đang nói hiện mờ. Mở lại phiên đã lưu cũng hiện như vậy.
- Nút **Copy** (copy toàn bộ `giờ  nội dung`) và **Xuất** (lưu .json / .txt qua trình chọn file, hoặc chia sẻ).
- Tick **"Tự chạy khi bật máy"** để khởi động cùng máy. Bấm **"Bỏ tối ưu pin"** để chạy ngầm ổn định hơn.
- File lưu tại `Android/data/com.dsoft.voicenote/files/transcripts/`, mỗi phiên một file `yyyy-MM-dd HH-mm-ss.json` (hoặc mỗi ngày một file):

```json
{
  "app": "VoiceNote", "version": 2, "language": "vi",
  "start": "2026-10-18T08:15:32.120+07:00",
  "entries": [
    { "time": "08:15:32", "timestamp": 1792286132120, "iso": "2026-10-18T08:15:32.120+07:00", "text": "Xin chào mọi người" }
  ]
}
```

File `.txt` của bản cũ vẫn mở được.

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

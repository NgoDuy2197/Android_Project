# HealthAssistant

Ghi lại bữa ăn, rồi bấm **“Xem xét”** để AI ước tính calo + nhận xét sức khoẻ theo hồ sơ (chiều cao,
cân nặng, giới tính, tuổi, mức vận động) và **mục tiêu cân nặng** của bạn. Native Android (Kotlin), giao diện kiểu iOS.

## Tính năng
- **Nhật ký bữa ăn** theo ngày (Sáng / Trưa / Tối / Phụ), nhập món dạng chữ tự do (mỗi dòng một món).
- **Widget màn hình chính** — chạm là mở ngay màn ghi bữa cho hôm nay (ghi nhanh, không cần mở app).
- **Uống nước**: đặt **mục tiêu ml/ngày** + **dung tích 1 cốc**, widget riêng chạm `+` là cộng 1 cốc cho hôm nay, dữ liệu lưu cục bộ, xem lại dạng **lịch tháng** (ô ngày tô đầy theo tỉ lệ nước đã uống / mục tiêu).
- **Widget khoá màn hình** — chạm một cái là tắt + khoá màn hình (dùng quyền *device admin* với **duy nhất** chính sách `force-lock`; tắt công tắc trong app là thu hồi quyền ngay).
- **Xem xét bằng AI**: từng bữa hoặc **cả ngày** → calo tổng, đạm/tinh bột/béo, nhận xét so với mục tiêu, gợi ý, cảnh báo.
- **Hồ sơ sức khoẻ**: tự tính **BMI, BMR, TDEE** và **calo mục tiêu/ngày** (công thức Mifflin–St Jeor) theo mục tiêu giảm/giữ/tăng cân + tốc độ mong muốn + ghi chú riêng (ăn chay, nhiều đạm, tiểu đường…).
- **2 cơ chế AI** (chọn trong *Cài đặt AI*):
  1. **Offline (trên máy)** — tự import một model LLM định dạng **MediaPipe `.task`**; chạy hoàn toàn không cần mạng.
  2. **Gemini (online)** — nhập **API key** + chọn **model** (có sẵn preset, hoặc gõ tên tuỳ ý).
- Khi chưa cấu hình AI, vẫn **ước tính thô** được bằng bảng calo có sẵn (gắn nhãn rõ “chưa dùng AI”).

## Build
```bat
build_apk.bat            :: tạo app\build\outputs\apk\debug\app-debug.apk + HealthAssistant.apk
build_apk.bat install    :: build rồi adb install vào máy đang cắm
build_apk.bat clean
```
Yêu cầu JDK 17 (tự dò JBR của Android Studio) + Android SDK.

## Cấu hình AI

### Cách 1 — Gemini (online, dễ nhất)
1. Lấy API key: https://aistudio.google.com/apikey
2. Mở app → **⚙️ Cài đặt AI** → chọn **Gemini** → dán key → chọn model (mặc định `gemini-2.5-flash`).
3. Bấm **Kiểm tra Gemini**. Lưu ý: dữ liệu bữa ăn sẽ gửi tới Google khi “Xem xét”.

### Cách 2 — Offline (model tự import, không cần mạng)
App dùng **MediaPipe LLM Inference** (`tasks-genai`), nên model phải ở định dạng **`.task`** (KHÔNG dùng GGUF/Ollama).

Gợi ý model (tải về máy rồi **⚙️ Cài đặt AI → Offline → Import model (.task)**):

| Model | Dung lượng (ước tính) | Ghi chú |
|---|---|---|
| **Gemma 3 1B IT** (khuyến nghị, nhẹ) | ~0.5–0.6 GB | `litert-community/Gemma3-1B-IT` trên Hugging Face — có file `.task` chạy LLM Inference trên Android |
| Gemma 3n E2B / E4B | ~1.3–4 GB | Mạnh hơn, cần máy khoẻ; định dạng có thể là `.litertlm` — xem kỹ trang model |

Nguồn lấy model + hướng dẫn chính thức:
- **Gemma 3 1B IT (`.task`)** 👉 https://huggingface.co/litert-community/Gemma3-1B-IT — vào tab *Files and versions* tải `gemma3-1b-it-int4.task` (~555 MB). Repo *gated*: cần đăng nhập + chấp nhận license Gemma (hoặc dán **HF token** vào app để tải thẳng).
- Các model khác của tổ chức: https://huggingface.co/litert-community
- Google AI Edge – LLM Inference guide: https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference

> ⚠️ **Thành thật:** tốc độ/khả năng chạy offline **tuỳ máy** (khuyến nghị RAM ≥ 6–8 GB) — đây là [ước tính].
> Google đang chuyển MediaPipe LLM sang **LiteRT-LM** (`.litertlm`); bản app này nạp `.task`. Nếu sau này `.task` ngừng hỗ trợ sẽ cần cập nhật thư viện.

## Lưu ý quan trọng
- Mọi con số calo/dinh dưỡng là **ước tính**, **không thay thế** tư vấn dinh dưỡng/y tế chuyên môn.
- Dữ liệu (hồ sơ, bữa ăn) lưu cục bộ trong bộ nhớ app. Chỉ gửi ra ngoài khi bạn chọn engine Gemini.

## Cấu trúc
- `Store.kt` — hồ sơ + nhật ký bữa ăn (JSON trong filesDir) + công thức BMR/TDEE/BMI.
- `Ai.kt` — dựng prompt, `GeminiClient` (REST), `OfflineClient` (MediaPipe LLM), parse JSON, `LocalEstimator` (ước tính thô).
- `MainActivity` (dashboard) · `MealEditorActivity` · `ReviewActivity` · `ProfileActivity` · `AiSettingsActivity`.
- `QuickMealWidget` + `QuickMealActivity` — widget ghi nhanh.
- `Water.kt` — cấu hình nước + log `ngày -> ml` (JSON trong filesDir, theo từng user) · `WaterWidget` · `WaterActivity` (lịch tháng).
- `LockAdminReceiver` (`force-lock`) · `LockWidget` · `LockActivity` (trampoline xin quyền) · `LockSettingsActivity` (bật/tắt quyền).

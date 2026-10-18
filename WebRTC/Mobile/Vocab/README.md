# Từ Vựng — bộ từ + widget học từ

App Android (Flutter + widget native Kotlin).

- **Bộ từ (Collection)**: tạo / sửa / xoá; mỗi bộ có 1–3 dòng, mỗi dòng chọn ngôn ngữ (Trung, Anh, Nhật, Việt, hoặc Khác như pinyin/romaji). Thêm từng từ, hoặc **dán nhiều từ** (cột cách nhau bằng `|` hoặc Tab). Tìm kiếm, lọc Chưa thuộc / Đã thuộc, quẹt trái để xoá từ, nút ✓ đánh dấu đã thuộc.
- **Nhập / Xuất JSON** (menu `…`): xuất tất cả để sao lưu, xuất từng bộ để chia sẻ; nhập file: bộ trùng id được **khôi phục** (ghi đè), bộ mới được thêm.
- **TTS**: nút loa cạnh từng dòng, đọc bằng giọng Android chuẩn (zh-CN / en-US / ja-JP / vi-VN). Máy chưa có giọng → app gợi ý mở màn hình cài giọng.
- **Widget** (tab Widget hoặc giữ màn hình chính → Widgets → Từ Vựng):
  - Chọn bộ từ cho từng widget, hiển thị **1 / 2 / 3 dòng**, tự đổi từ (tắt / 30 phút / 1 giờ / 3 giờ / mỗi ngày).
  - 🔊 đọc từ · ✓ **đã thuộc** (không hiện lại) · ↻ **đổi từ**; chạm vào từng dòng để nghe dòng đó.
- Có 4 bộ mẫu: Chào hỏi 3 thứ tiếng (Anh–Trung–Nhật), HSK 1, English basics, 日本語 N5.

Build: `mobile\build_apk.bat` → `mobile\Vocab.apk`.

Định dạng JSON:
```json
{"format":"vocab-collections","version":1,"collections":[
  {"id":"…","name":"HSK 1","fields":[{"name":"Hán tự","lang":"zh"},{"name":"Pinyin","lang":""},{"name":"Nghĩa","lang":"vi"}],
   "words":[{"id":"…","values":["爱","ài","yêu"],"learned":false}]}]}
```

## Tạo bộ từ bằng AI rồi nhập vào app

File mẫu: **`Vocab/vocab_sample.json`** (3 bộ: Anh·Trung·Nhật 3 dòng, HSK có pinyin, IELTS 2 dòng). Đưa file này cho AI làm mẫu, nhận file JSON mới, chép sang điện thoại rồi trong app chọn **`…` → Nhập JSON (khôi phục)**.

Quy tắc định dạng:

- Gốc: `{"format": "vocab-collections", "version": 1, "collections": [ … ]}`.
- Mỗi bộ: `id` (chuỗi **duy nhất**), `name`, `fields` (1–3 dòng), `words`.
- `fields[i].lang`: `zh` Trung · `en` Anh · `ja` Nhật · `vi` Việt · `""` không đọc (pinyin, romaji…). Dòng có `lang` sẽ có nút loa.
- Mỗi từ: `id` duy nhất, `values` = **đúng số phần tử bằng số `fields`**, theo cùng thứ tự; `learned: false`.
- Nhập file: bộ trùng `id` với bộ đang có sẽ **bị thay thế**; `id` mới thì **thêm** bộ mới.

Câu lệnh mẫu gửi AI (đính kèm `vocab_sample.json`):

```text
Dựa đúng định dạng file vocab_sample.json đính kèm, tạo một file JSON mới gồm 1 bộ từ:
- Tên bộ: "<ví dụ: Công sở – Anh · Trung · Nhật>"
- Các dòng (fields): <ví dụ: English (en), 中文 (zh), 日本語 (ja)>
- 50 từ/cụm từ thông dụng chủ đề <chủ đề>, mỗi từ có values đúng thứ tự các dòng trên.
- id của bộ và của từng từ phải duy nhất (vd "office-001", "office-002"…), learned = false.
Chỉ trả về JSON hợp lệ, không giải thích.
```

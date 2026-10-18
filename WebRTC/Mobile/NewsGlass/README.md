# Tin Tức — đọc RSS kiểu iOS

App Android (Flutter + nền native Kotlin/WorkManager).

- **Nguồn RSS**: mặc định VnExpress *Tin xem nhiều* và *Thế giới*; thêm / sửa / xoá / bật-tắt, nút khôi phục 2 nguồn mặc định.
- **Giao diện**: mặc định **Yahoo cổ điển** (dải tím, tiêu đề xanh tím), thêm **Rosy bloom**, **Ocean breeze**, **Midnight**; chọn **ảnh nền** riêng (có làm mờ). Mọi cấu hình (nguồn, theme, ảnh nền, bộ lọc, thông báo, chu kỳ) lưu ở `config.json`, mở lại app giữ nguyên.
- **Trang chính**: bộ lọc **Chưa đọc** (mặc định) / **Tất cả**; **quẹt** một tin = đã đọc (ở *Chưa đọc* tin biến mất); chạm để mở bài (cũng tính đã đọc); kéo xuống để tải ngay; nút đánh dấu tất cả đã đọc.
- **Chạy ngầm**: WorkManager quét mỗi 1 giờ (đổi được 30 phút–6 giờ) → ghi `news.json`. Trang chính và widget đều đọc từ file này.
- **Thông báo tin mới**: ≤ 5 tin → mỗi tin 1 thông báo (chạm mở bài); > 5 tin → 1 thông báo "Có N tin mới". Lần tải đầu tiên không bắn thông báo.
- **Widget**: tin mới nhất chưa đọc; nút ▸ nhỏ = đánh dấu đã đọc và sang tin tiếp; chạm tiêu đề để mở bài.

Build: `mobile\build_apk.bat` → `mobile\NewsGlass.apk`.

Lưu ý: feed `tin-xem-nhieu.rss` của VnExpress hiện chỉ trả bài cũ (2021); `the-gioi.rss` vẫn cập nhật.

## Chống bị tắt khi vuốt bỏ app (Realme / OPPO / ColorOS)

ColorOS **buộc dừng** app bị vuốt bỏ ở đa nhiệm → Android huỷ mọi lịch / job của app. Cách giữ app:

1. **Khoá app trong đa nhiệm**: mở đa nhiệm → ⋮ trên thẻ app → *Khoá (Lock)*. Đơn giản và chắc chắn nhất.
2. **Watchdog** (giống `volte_guard`): script `android/app/src/main/assets/keepalive.sh` chạy bằng quyền shell, tách khỏi app; cứ mỗi chu kỳ kiểm tra, nếu app đang ở trạng thái *stopped* thì gửi broadcast đánh thức (xoá trạng thái stopped) để app đặt lại lịch.
   - Có **Shizuku**: app tự bật watchdog (thẻ *Bảo vệ chạy nền* / mục *Chạy nền*).
   - Không có Shizuku: cắm máy tính, chạy `mobile\keepalive.bat` (`keepalive.bat stop` để tắt).
   - Watchdog tắt khi khởi động lại máy → bật lại Shizuku / chạy lại `keepalive.bat`.
   - Log: `adb logcat -s KeepAlive`
3. Bỏ tối ưu pin + cho phép tự khởi chạy (có nút trong app).
Ngoài WorkManager, app đặt thêm **báo thức chính xác** mỗi chu kỳ (FetchAlarm) vì ColorOS hay hoãn job khi máy nghỉ; tự đặt lại sau khi khởi động máy.

## Tóm tắt bằng AI (Gemini)

- Cài đặt → **AI tóm tắt (Gemini)**: nhập API key (lấy miễn phí tại aistudio.google.com → Get API key). Key chỉ lưu trên máy (`config.json`).
- Trang chính → nút **✨**: gửi tiêu đề của mọi tin **chưa đọc** (tối đa 200) cho model **`gemini-2.5-flash-lite`**, hiện bản tóm tắt tiếng Việt nhóm theo chủ đề (có nút sao chép), rồi **đánh dấu các tin đó đã đọc**. Gọi AI lỗi (sai key, mất mạng) thì không đánh dấu tin nào.
- Code: `lib/gemini.dart` (REST `generateContent`, header `x-goog-api-key`).

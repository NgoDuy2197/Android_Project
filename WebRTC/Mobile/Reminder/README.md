# Nhắc Nhở — đếm ngược, lặp lại, theo ngày giờ

App Android (Flutter + báo thức native Kotlin).

- **Kiểu nhắc**:
  - **Đếm ngược** (giờ:phút:giây, bắt đầu khi lưu; bật lại công tắc để đếm lại).
  - **Lặp lại** mỗi N phút / giờ / ngày / tuần / tháng, tính từ một mốc (vd mỗi ngày 7:00, mỗi tháng ngày 15).
  - **Ngày giờ** chính xác (một lần).
- **Âm thanh**: mặc định **chuông điện thoại**; hoặc **ghi âm** trong app (nghe thử, ghi lại).
- **Máy tắt tiếng vẫn kêu**: phát qua kênh **báo thức** (không bị chế độ im lặng/rung tắt), tự **tăng âm lượng báo thức** nếu đang thấp rồi trả lại như cũ; kèm rung; kêu 15 giây–5 phút.
- Hẹn giờ bằng `AlarmManager.setAlarmClock` (chính xác, chạy cả khi máy ngủ sâu), tự đặt lại sau khi khởi động máy / đổi giờ.
- Khi kêu: màn hình **Tắt / Báo lại 5 phút** hiện cả lúc khoá máy + thông báo có nút.
- **Giao diện khi reo** (mỗi nhắc nhở một cấu hình):
  - **Biểu tượng emoji**: chọn trong bảng hoặc gõ emoji bất kỳ. Emoji hiện ở danh sách, thông báo và màn hình reo.
  - **Ảnh nền** chọn từ máy, có phủ lớp tối để chữ dễ đọc. Bỏ ảnh thì dùng nền gradient mặc định.
- **Sắp đến giờ**: khi một nhắc nhở còn dưới 5 phút, app hiện thông báo đếm ngược (hiện cả trên màn hình khoá, không kêu). Thông báo tự mất khi chuông reo.
- App nhắc nếu thiếu quyền (thông báo, báo thức chính xác, hiện toàn màn hình).

Build: `mobile\build_apk.bat` → `mobile\Reminder.apk`.

Lưu ý: nếu bật **Không làm phiền** và chặn cả báo thức, Android không cho đổi âm lượng; còn lại chuông vẫn phát trên kênh báo thức.

## Chống bị tắt khi vuốt bỏ app (Realme / OPPO / ColorOS)

ColorOS **buộc dừng** app bị vuốt bỏ ở đa nhiệm → Android huỷ mọi lịch / job của app. Cách giữ app:

1. **Khoá app trong đa nhiệm**: mở đa nhiệm → ⋮ trên thẻ app → *Khoá (Lock)*. Đơn giản và chắc chắn nhất.
2. **Watchdog** (giống `volte_guard`): script `android/app/src/main/assets/keepalive.sh` chạy bằng quyền shell, tách khỏi app; cứ mỗi chu kỳ kiểm tra, nếu app đang ở trạng thái *stopped* thì gửi broadcast đánh thức (xoá trạng thái stopped) để app đặt lại lịch.
   - Có **Shizuku**: app tự bật watchdog khi mở app (không còn thẻ *Bảo vệ chạy nền* trên màn hình chính).
   - Không có Shizuku: cắm máy tính, chạy `mobile\keepalive.bat` (`keepalive.bat stop` để tắt).
   - Watchdog tắt khi khởi động lại máy → bật lại Shizuku / chạy lại `keepalive.bat`.
   - Log: `adb logcat -s KeepAlive`
3. Bỏ tối ưu pin + cho phép tự khởi chạy (có nút trong app).
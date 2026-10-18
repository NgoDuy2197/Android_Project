# MacroIntegrate

App Android siêu nhẹ (~0.8 MB, Kotlin thuần, không thư viện ngoài) làm "tay sai" cho MacroDroid:
gọi một chức năng là nó gửi dữ liệu lên Discord qua webhook.

## Chức năng
- **Gửi vị trí hiện tại** lên Discord (toạ độ, độ chính xác, link Google Maps).
- **Gửi ảnh màn hình** hiện tại lên Discord.

## Cấu hình webhook
- Mở app, dán **link Discord webhook** (Server Settings › Integrations › Webhooks › New Webhook › Copy Webhook URL) và đặt tên hiển thị.
- Hoặc khai sẵn link mặc định khi build: sửa file **`webhook.properties`** ở gốc project:
  ```
  DISCORD_WEBHOOK=https://discord.com/api/webhooks/.../...
  ```
  (xem `webhook.properties.example`; file này được `.gitignore`). Để trống ô trong app = dùng link mặc định này.

## Gọi từ MacroDroid
Ba cách, chọn cách nào cũng được:

1. **Shortcut (phím tắt):** MacroDroid › Action › *Mở ứng dụng / phím tắt* › chọn phím tắt
   **Gửi vị trí** hoặc **Ảnh màn hình** của MacroIntegrate.
2. **Launch Activity:**
   - Package: `com.dsoft.macrointegrate`
   - Class: `com.dsoft.macrointegrate.ActionActivity`
   - Extra (text): `action` = `location` hoặc `screenshot`
3. **Intent action:** `com.dsoft.macrointegrate.SEND_LOCATION` hoặc `com.dsoft.macrointegrate.SEND_SCREENSHOT`
   (gửi tới `ActionActivity`).

**Tùy chọn mỗi lần gọi** (extra dạng text):
- `webhook` — đổi link webhook cho riêng lần gọi đó (chỉ áp dụng cho vị trí).
- `message` — ghi chú kèm theo (chỉ áp dụng cho vị trí).

`ActionActivity` chạy ẩn (nền trong suốt), làm xong hiện một toast rồi tự tắt.

## Quyền
- **Vị trí:** cấp quyền vị trí. Nếu MacroDroid gọi lúc màn hình tắt, nên chọn **"Luôn cho phép"**.
- **Chụp màn hình:** Android bắt buộc đồng ý ghi màn hình. Vào app bấm **bật "Chụp màn hình"** một lần — có một dịch vụ nền (thông báo thường trực) giữ quyền để MacroDroid chụp bất cứ lúc nào. Khởi động lại máy thì bật lại.

## Build
```
build_apk.bat          -> MacroIntegrate.apk (debug), đồng thời copy sang WebRTC\__Result
build_apk.bat install  -> build + adb install
```

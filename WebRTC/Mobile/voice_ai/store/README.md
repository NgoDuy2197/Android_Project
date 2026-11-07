# Google Play — tài sản cửa hàng (voice_ai)

Thư mục này chứa mọi thứ cần để đăng app lên Google Play Console. Kéo ảnh vào đúng ô, điền `listing-vi.md`.

## Ảnh bắt buộc
| Loại | Kích thước | Định dạng | Ghi vào |
|------|-----------|-----------|---------|
| Icon cửa hàng | 512 × 512 | PNG 32-bit, không trong suốt | `graphics/icon-512.png` |
| Feature graphic | 1024 × 500 | PNG/JPG, không trong suốt | `graphics/feature-1024x500.png` |
| Ảnh màn hình điện thoại | tối thiểu 2, tối đa 8; cạnh 320–3840px | PNG/JPG | `screenshots/phone/` |
| Ảnh máy tính bảng 7" | tuỳ chọn | PNG/JPG | `screenshots/tablet7/` |
| Ảnh máy tính bảng 10" | tuỳ chọn | PNG/JPG | `screenshots/tablet10/` |

## Mẹo chụp ảnh màn hình
- Dùng khung điện thoại sạch, tắt thanh thông báo rối mắt.
- `adb exec-out screencap -p > screenshots/phone/01.png` để chụp nhanh từ máy thật.
- 2 ảnh đầu quan trọng nhất (hiện ở danh sách) — chọn màn hình đắt giá nhất.

## Chính sách cần kiểm trước khi nộp
- Privacy Policy (URL công khai) — bắt buộc nếu xin quyền nhạy cảm.
- Data safety form — khai đúng dữ liệu thu thập.
- Target API level đạt yêu cầu hiện hành của Play.
- Nếu dùng quyền nhạy cảm (MANAGE_EXTERNAL_STORAGE, Accessibility, camera nền...) cần giải trình.

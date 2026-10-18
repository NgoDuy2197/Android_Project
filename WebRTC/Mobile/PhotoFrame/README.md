# Khung Ảnh — widget ảnh với khung tuỳ biến

App Android (Flutter + widget native Kotlin). App tạo và quản lý các **khung**; mỗi widget trên màn hình chính hiển thị một khung.

## Tính năng

- **Nguồn ảnh** cho từng khung: ảnh mẫu, **thư mục** (có/không gồm thư mục con, tự quét lại mỗi 6 giờ, nút *Quét lại*) hoặc **chọn từng ảnh**. Quyền đọc được giữ vĩnh viễn (SAF).
- **Số ảnh trong khung: 1 / 2 / 3 / 4** — bố cục **tự sắp xếp** (không chia ô đều): thử mọi thứ tự và mọi cách chia hàng/cột kiểu mosaic, chọn cách cắt ảnh ít nhất theo tỉ lệ thật của từng ảnh.
- **Hình khung** (13 loại): bo góc, iOS (squircle), vuông, tròn, bầu dục, trái tim, ngôi sao, lục giác, thoi, vòm cửa, bông hoa, tem thư, Polaroid.
- **Viền**: độ dày + màu; **nền khung**: màu, lề trong, **độ đậm/mờ** của khung; **đổ bóng** (bật/tắt, độ lớn); **ánh kính** kiểu iOS.
- **Ảnh**: khoảng cách giữa ảnh, bo góc ảnh, **bộ lọc màu** (trắng đen, sepia, ấm, lạnh, cổ điển, rực rỡ), **hiện ngày chụp** (EXIF).
- **Chuyển ảnh**: **tuần tự** (theo tên) hoặc **ngẫu nhiên** (tránh lặp lại ngay); tự đổi mỗi 15 phút … mỗi ngày; **chạm widget** = đổi ảnh tiếp hoặc mở app.
- **Tự co giãn khi đổi cỡ widget**: khung được vẽ thành 1 ảnh đúng kích thước pixel thật của widget; mọi độ dày/khoảng cách/bóng tính theo cạnh ngắn của widget nên kéo to/nhỏ vẫn cân đối.
- **Xem trước trực tiếp** trong app bằng đúng bộ vẽ của widget, thử các cỡ 2×2 / 4×2 / 2×4 / 4×4, nút đổi ảnh xem thử.
- Nhân bản khung, xoá khung, gán khung cho từng widget, đổi ảnh widget từ app; 5 khung mẫu sẵn.

Build: `mobile\build_apk.bat` → `mobile\PhotoFrame.apk`.

## Kiến trúc

| File | Vai trò |
|---|---|
| `FrameRenderer.kt` | Vẽ khung: hình dạng, bố cục ảnh tự động, viền, bóng, bộ lọc, ngày chụp. |
| `FrameStore.kt` | Đọc `frames.json`, quét ảnh thư mục (cache), trạng thái từng widget, chọn ảnh tiếp (tuần tự/ngẫu nhiên). |
| `PhotoWidget.kt` | Widget provider (vẽ lại khi đổi cỡ), bộ hẹn giờ tự đổi ảnh, màn chọn khung khi đặt widget. |
| `lib/editor_page.dart` | Màn chỉnh khung + xem trước. |

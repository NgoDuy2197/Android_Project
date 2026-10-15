# TryOn — Thử đồ realtime qua camera

Mặc thử sản phẩm (ảnh) lên người trong camera theo thời gian thực, chụp ảnh / quay video. Hai bản dùng chung ảnh mẫu và công thức gắn đồ:

| Thư mục | Nền tảng | Build | Kết quả |
|---|---|---|---|
| `mobile/` | Android (Flutter + Google ML Kit) | `mobile\build_apk.bat` | `mobile\TryOn.apk` |
| `windows/` | Windows (Python + MediaPipe + PySide6), dùng **webcam máy tính** | `windows\build_exe.bat` | `windows\TryOn.exe` |

## Tính năng (cả hai bản)

- **Giỏ hàng local**: thêm ảnh sản phẩm, tự tách nền trơn hoặc **chọn màu nền** để xoá (vd. phông xanh lá), đặt tên, gán bộ phận. Có sẵn 10 sản phẩm mẫu + 4 background mẫu.
- **10 bộ phận**, mỗi bộ phận 1 món, mặc cùng lúc. Mỗi bộ phận có lựa chọn **Không**:

  | Bộ phận | Ví dụ | Gắn theo |
  |---|---|---|
  | Đầu | mũ | tai, mắt |
  | Hào quang | vòng thiên sứ, trang trí trên đầu | phía trên đầu |
  | Mặt | khẩu trang | mũi, miệng |
  | Mắt | kính | mắt |
  | Cánh | cánh thiên thần (nằm **sau** người) | vai |
  | Áo | áo | vai, hông |
  | Quần | quần | hông → cổ chân |
  | Chân váy | chân váy | eo → qua gối |
  | Váy dài | váy liền thân tới chân | vai → chân |
  | Giày | giày (2 chiếc) | gót, mũi chân |

  Quần ↔ chân váy ↔ váy dài loại trừ nhau; váy dài cũng thay cho áo.
- **Size tự động** theo khung xương + chỉnh tay: kích thước, độ rộng, độ dài, trái/phải, lên/xuống, lật ngang.
- **Background**: chỉ giữ lại người, thay nền bằng ảnh mẫu hoặc ảnh của bạn.
- Chế độ **ẢNH · VIDEO · ẢNH TĨNH** (ảnh tĩnh: chụp toàn thân / chọn ảnh có sẵn rồi tự gắn đồ).
- **Ẩn giao diện** để xem toàn màn hình.

## Windows

- Chạy `windows\build_exe.bat` (cần Python 3.9–3.13 64-bit và mạng lần đầu). Script tự tạo `.venv`, cài thư viện, tải model nhận diện cơ thể của Google (~9 MB), rồi build `TryOn.exe` (một file, ~160 MB).
- Chạy từ mã nguồn: `windows\run.bat` (sau khi đã build ít nhất một lần để có `.venv`).
- Phím tắt: **Space** = chụp / quay / dừng, **Esc** = ẩn/hiện giao diện (toàn màn hình).
- Ảnh và video lưu ở `Pictures\TryOn`. Giỏ hàng lưu ở `%APPDATA%\TryOn`.
- Chọn webcam ở ô **Camera 0/1/2…**; bỏ tick **Lật gương** nếu muốn hình không bị lật.
- Ảnh tĩnh: **⏱ Chụp toàn thân** đếm ngược 5 giây để bạn kịp lùi ra xa.

## Giới hạn

- Đồ là ảnh 2D dán theo khung xương, không bọc theo nếp vải.
- Video bản Android không có tiếng; video bản Windows lưu MP4 (MPEG-4), không tiếng.
- Tỉ lệ gắn đồ tự động được đặt theo ước lượng; dùng thanh chỉnh nếu chưa khớp.

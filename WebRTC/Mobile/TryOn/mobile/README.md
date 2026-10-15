# TryOn — Thử đồ realtime qua camera

Ứng dụng Flutter (Android) dùng để mặc thử sản phẩm lên người trong camera theo thời gian thực, sau đó chụp ảnh.

- **Giỏ hàng local**: nhập ảnh sản phẩm (từ thư viện hoặc chụp), tự tách nền trơn, đặt tên, gán bộ phận. Dữ liệu lưu trong máy (`cart.json` + ảnh PNG trong thư mục riêng của app).
- **10 bộ phận** (Đầu, Hào quang, Mặt, Mắt, Cánh, Áo, Quần, Chân váy, Váy dài, Giày), mỗi bộ phận mặc **1 sản phẩm**, mặc **cùng lúc** nhiều bộ phận; bảng chi tiết xem ..\README.md. Quần ↔ chân váy ↔ váy dài loại trừ nhau, váy dài thay cho áo.
- **Background** (nút **Nền** cạnh nút chụp): tách người bằng ML Kit Selfie Segmentation, thay phần còn lại bằng ảnh nền; cánh cũng dùng lớp tách người này để nằm sau lưng.
- Bấm vào bộ phận → camera **khoanh sáng vùng đó** trên người, mở bảng chọn sản phẩm:
  - Chọn / bỏ chọn sản phẩm, **Thêm** ảnh mới, **xoá** sản phẩm (nút ✕ hoặc giữ lâu).
  - **Chỉnh size thủ công**: Kích thước, Độ rộng, Độ dài, Trái↔phải, Lên↕xuống, Lật ngang; nút "Về size tự động".
- Áo/quần **tự co giãn** theo khoảng cách vai–hông, hông–cổ chân; đồ xoay theo độ nghiêng của đầu/vai.
- **Chế độ** (dưới nút chụp, kiểu camera iOS): **VIDEO · ẢNH · ẢNH TĨNH**.
  - **ẢNH**: ghép đồ vào khung hình và lưu vào Thư viện ảnh (album **TryOn**).
  - **VIDEO**: quay MP4 (H.264, không tiếng) có sẵn đồ đang mặc, lưu vào album **TryOn**.
  - **ẢNH TĨNH**: chụp ảnh toàn thân bằng camera của máy hoặc chọn ảnh có sẵn → app tự nhận diện cơ thể (model chính xác cao) và gắn đồ; chỉnh size như chế độ camera rồi bấm **Lưu ảnh**.
- **Nút ẩn giao diện** (biểu tượng mắt gạch): chỉ còn hình camera/ảnh; chạm màn hình hoặc nút mắt mờ góc phải để hiện lại (nút chuyển đỏ nếu đang quay video).
- Thêm ảnh sản phẩm: **Chọn màu nền** bằng cách chạm vào ảnh gốc (vd. phông xanh lá) để xoá đúng màu đó; chọn được nhiều màu, có độ mạnh và tuỳ chọn chỉ xoá vùng nối với viền.
- Nút giỏ hàng (góc phải): xem toàn bộ sản phẩm theo bộ phận, mặc/gỡ, xoá.

## Build APK

Chạy `build_apk.bat` (cần Flutter SDK trong PATH). File APK: `TryOn.apk` ở thư mục gốc project.

## Kiến trúc

| File | Vai trò |
|---|---|
| `lib/tryon_page.dart` | Camera (stream NV21), gọi ML Kit Pose Detection, vẽ lớp đồ, chụp & lưu ảnh. |
| `lib/outfit_painter.dart` | Làm mượt landmark, tính vị trí/kích thước/góc xoay cho từng bộ phận, vẽ đồ. |
| `lib/part_sheet.dart` | Bảng chọn sản phẩm + thanh chỉnh size theo bộ phận. |
| `lib/import_page.dart` | Chọn ảnh, tách nền, thêm vào giỏ. |
| `lib/cart_page.dart` | Giỏ hàng. |
| `lib/video_recorder.dart` + `android/.../VideoEncoder.kt` | Quay video: mỗi khung NV21 từ camera + lớp đồ (RGBA nửa độ phân giải) được trộn và mã hoá H.264 native (MediaCodec/MediaMuxer). |
| `lib/photo_page.dart` | Chế độ ảnh tĩnh. |
| `lib/part_bar.dart` | Thanh 6 bộ phận dùng chung. |
| `lib/models.dart` | Sản phẩm, chỉnh size, lưu trữ JSON. |
| `lib/image_tools.dart` | Tách nền (flood fill từ viền), chuyển NV21 → RGBA (chạy trong isolate). |

Nhận diện cơ thể: **Google ML Kit Pose Detection** (33 điểm, chạy offline trên máy, model được đóng gói sẵn trong APK).

## Mẹo để đồ khớp đẹp

- Dùng ảnh sản phẩm **nền trắng/trơn** hoặc PNG nền trong suốt, chụp chính diện, sản phẩm chiếm gần hết khung.
- Giày: dùng ảnh 1 chiếc nhìn ngang; app tự lật cho chân còn lại. Nếu bị ngược, bật "Lật ngang".
- Đứng cách điện thoại 2–3 m để camera thấy cả người khi thử quần/giày.
- Nếu đồ di chuyển ngược chiều với người: menu `…` → bật "Đảo trái ↔ phải lớp đồ".

## Giới hạn

- Đồ là ảnh 2D dán theo khung xương, không bọc theo nếp vải hay che khuất 3D.
- Tách nền tự động chỉ xử lý nền trơn; ảnh nền phức tạp nên tách sẵn thành PNG trong suốt.
- Ảnh chụp lấy từ khung hình phân tích (tối đa 1080×1920), có thể thấp hơn độ phân giải camera tối đa.

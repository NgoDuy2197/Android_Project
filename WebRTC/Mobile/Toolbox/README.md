# Tiện ích (Toolbox)

Ứng dụng Android gom các tiện ích nhỏ. Viết bằng Kotlin thuần, không dùng thư viện ngoài.

## 1. FTP Server
Dùng để chép file giữa máy tính và điện thoại qua Wi-Fi.
- Chọn **thư mục chia sẻ**: chọn bằng trình chọn thư mục, gõ đường dẫn, hoặc dùng gốc bộ nhớ trong. Máy khách chỉ thấy được bên trong thư mục này.
- Mặc định: cổng `2121`, tài khoản `admin`, mật khẩu `123456`. Đổi được cả ba trong app (có nút "Khôi phục mặc định"). Xóa trống tài khoản thì đăng nhập ẩn danh. Có chế độ chỉ đọc.
- Server chạy dưới dạng foreground service, giữ Wi-Fi lock và wake lock, nên vẫn chạy khi tắt màn hình. Thông báo có nút **Dừng**.
- Hỗ trợ: PASV/EPSV/PORT, LIST/MLSD/NLST, RETR/STOR/APPE, REST (tải tiếp), MKD/RMD/DELE/RNFR-RNTO, SIZE/MDTM, tên file UTF-8.
- File tải lên được báo cho MediaScanner nên hiện ngay trong Thư viện ảnh.

**Kết nối từ máy tính:** máy tính và điện thoại cùng Wi-Fi (hoặc máy tính bắt hotspot của điện thoại).
- Windows: gõ `ftp://<IP>:2121` vào thanh địa chỉ File Explorer.
- Hoặc dùng FileZilla / WinSCP.

**Quyền cần cấp:** "Truy cập tất cả tệp" (Android 11 trở lên).

## 2. Làm tối màn hình
Phủ một lớp đen trong suốt, không nhận chạm, lên toàn màn hình nên tối hơn mức sáng thấp nhất của hệ thống.
- Thanh trượt từ 0 đến 98%, chỉnh trực tiếp khi đang bật. Thông báo có các nút Sáng hơn / Tối hơn / Tắt.
- Có ô **Làm tối** trong Cài đặt nhanh.
- Tùy chọn hạ độ sáng hệ thống xuống thấp nhất, cần quyền "Sửa cài đặt hệ thống". Độ sáng cũ được trả lại khi tắt làm tối.
- Từ Android 12, lớp phủ thường bị giới hạn khoảng 79% để không chặn chạm. Muốn tối om:
  1. Vào Cài đặt › Trợ năng và bật **Toolbox – Làm tối & menu nguồn**. Lớp phủ trợ năng được phép tối hơn mà vẫn chạm được ứng dụng bên dưới.
  2. Nếu mục này bị mờ ("Cài đặt bị hạn chế"), vào Cài đặt › Ứng dụng › Tiện ích › ⋮ › **Cho phép cài đặt bị hạn chế**.

**Quyền cần cấp:** "Hiển thị trên ứng dụng khác".

## 3. Quét mã vạch / QR
- Quét mọi định dạng: QR, Data Matrix, Aztec, PDF417, EAN-13/8, UPC-A/E, Code 128/39/93, Codabar, ITF.
- Kết quả có nút **Copy**. Nếu nội dung là liên kết thì có thêm nút **Mở liên kết**.
- Lưu lịch sử 30 mã gần nhất; chạm vào một mã trong lịch sử để xem lại.
- Dùng Google code scanner (`play-services-code-scanner`). Giao diện camera chạy trong Google Play services nên app không cần quyền camera. Máy phải có Google Play services.

## 4. Dịch màn hình
- Bấm **Bật nút dịch nổi** để hiện nút tròn `文A` nổi trên mọi ứng dụng, kể cả game.
- Chạm nút: app chụp màn hình, nhận dạng chữ, rồi hiện ảnh toàn màn hình với bản dịch **đè lên đúng chỗ chữ gốc**. Ô dịch lấy màu nền xung quanh, cỡ chữ tự co cho vừa ô.
  - **Giữ tay trên ảnh** để xem bản gốc.
  - Bấm **✕** ở góc hoặc phím Back để quay lại.
- Kéo để di chuyển nút; **giữ lâu** để tắt. Thông báo cũng có nút Tắt.
- Ngôn ngữ gốc: Anh / Trung / Nhật / Việt. Ngôn ngữ đích: Việt (mặc định) / Anh / Trung / Nhật.
- Hai cách dịch, chọn trong app:
  - **Offline** (mặc định): Google ML Kit dịch ngay trên máy, không giới hạn. Mỗi thứ tiếng cần tải một gói khoảng 30 MB; tải sẵn trong mục "Gói ngôn ngữ offline", hoặc để lần dịch đầu tự tải.
  - **Online**: Google Dịch qua cổng web `translate.googleapis.com` (`client=gtx`). Cổng này miễn phí nhưng không chính thức, có thể bị giới hạn khi dùng nhiều.
- Nhận dạng chữ bằng ML Kit (bộ Latin, Trung, Nhật) chạy qua Google Play services.
- Có thể **dịch một ảnh có sẵn** trong máy.
- **Quyền cần cấp:**
  - "Hiển thị trên ứng dụng khác".
  - Quyền ghi màn hình: hỏi mỗi lần bật nút nổi; trên Android 14 hãy chọn "Toàn bộ màn hình".

## 5. Chia sẻ (văn bản + tệp)
- Bật server để có link `http://IP:8777`. Máy khác (cùng Wi-Fi/hotspot) mở link là vào được; chính máy chủ cũng mở link đó để dùng.
- Trang web là một **bảng chia sẻ**: danh sách các mục. Bấm vào một mục để mở ô **văn bản siêu to** và nút **gắn tệp**.
- Văn bản lưu trong bộ nhớ máy chủ (mất khi tắt server), đồng bộ giữa các máy.
- **Tệp: máy chủ KHÔNG lưu.** Khi một máy gắn tệp, trình duyệt của máy đó giữ tệp; lúc máy khác bấm Tải, dữ liệu được **truyền thẳng** từ trình duyệt người gắn, qua máy chủ, tới người tải (máy chủ chỉ chuyển tiếp, không ghi ra đĩa). Vì vậy trang của người gắn phải còn mở.
- Tệp do **chính máy chủ** gắn (nút "Gắn tệp từ máy này") thì được truyền thẳng từ bộ nhớ máy, không cần trang nào mở.

## 6. Khởi động lại / Tắt nguồn
Mỗi nút có **nhãn báo quyền**: `KHÔNG ROOT` (xanh) hay `ROOT` (đỏ), kèm dòng phụ nói rõ **lần bấm này sẽ chạy bằng cách gì** theo tình trạng máy hiện tại.

| Mục | Cần gì |
|---|---|
| Khởi động lại | **Không cần root** — Trợ năng, hoặc root |
| Tắt nguồn | **Không cần root** — Trợ năng, hoặc root |
| Khởi động lại nhanh (soft) | Bắt buộc root |
| Vào Recovery | Bắt buộc root |
| Vào Bootloader / Fastboot | Bắt buộc root |

**Thứ tự ưu tiên (mặc định):** thử cách không cần root trước — dịch vụ Trợ năng gọi
`GLOBAL_ACTION_POWER_DIALOG` để mở **menu nguồn của hệ thống**, bạn chọn "Khởi động lại" /
"Tắt nguồn" — chỉ khi không có mới dùng `su`.
- Dùng **chung dịch vụ Trợ năng với chức năng Làm tối** ("Toolbox – Làm tối & menu nguồn"),
  bật một lần là đủ cho cả hai.
- Có switch **"Ưu tiên dùng root khi đã có"** (mặc định tắt) để chạy thẳng lệnh root, đỡ phải
  bấm thêm một bước trong menu nguồn.
- Ba mục còn lại (soft reboot / Recovery / Bootloader) **không có API công khai** nào — bắt buộc
  root. Không có root thì cắm máy tính: `adb reboot recovery` / `adb reboot bootloader`.
- Màn hình có dòng trạng thái **Quyền root** và **Menu nguồn (Trợ năng)**; chạm vào dòng Trợ năng
  là mở thẳng trang Cài đặt. Hộp thoại xác nhận cũng ghi rõ sẽ chạy bằng cách nào.

## Android TV
- App hiện trong launcher của tivi (`LEANBACK_LAUNCHER` + banner riêng), và không bắt buộc màn cảm ứng / camera / cảm biến.
- Mọi hàng bấm được đều nhận focus bằng phím điều hướng của remote, có viền sáng khi được chọn.
- Chức năng **tự động bấm chữ trên màn hình** (tự bấm "Skip"…) nằm ở app riêng **AutoTap**, không ở đây.

## Build
```
build_apk.bat           -> Toolbox.apk (debug)
build_apk.bat install   -> build + adb install
```

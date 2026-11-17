# RemoteLink

Xem và điều khiển màn hình điện thoại Android từ **máy tính cùng mạng LAN**.
Một cặp project: **`Mobile/RemoteLink`** (app Android, native Kotlin → `RemoteLink.apk`) và
**`Desktop/RemoteLink`** (viewer Python/PySide6 → `RemoteLink.exe`). Cả hai được copy về `WebRTC/__Result`.

> ⚠️ Trạng thái: **code hoàn chỉnh, CHƯA test trên thiết bị thật.** Xem "Checklist test" cuối file.
> Các nhận định về hành vi Android bên dưới là [Inference] theo tài liệu/thiết kế API, chưa chạy kiểm chứng.

## Hỗ trợ cả 2 giao thức (chọn trong Cài đặt của app)
- **VNC server (RFB 3.8)** — mọi VNC viewer kết nối được (TigerVNC, RealVNC…) **và** viewer Desktop. Raw encoding + dirty bounding-box.
- **Stream (RustDesk-like)** — giao thức riêng, H.264 (MediaCodec) độ trễ thấp, dùng viewer Desktop.

## Capture màn hình — cả 3 giải pháp
1. **MediaProjection (không root)** — chạy mọi máy. [Inference] Stock Android bắt buộc hỏi đồng ý **1 lần mỗi phiên** (foreground service giữ phiên để chỉ hỏi 1 lần).
2. **Root (su)** — `screenrecord` (H.264) / `screencap` (VNC). Không hộp thoại, cần máy đã root.
3. **ADB / scrcpy-style (không cần app)** — nằm ở **viewer Desktop**: dùng `adb exec-out screenrecord` + `adb shell input`. Không root, không cần cài app lên máy; chỉ cần bật USB/wifi-debug.

## Điều khiển (input)
- **Accessibility (không root)** — cấp quyền **1 lần**, chạm/vuốt/kéo (API 26+)/một số phím + nút Back/Home/Recents.
- **Root (su)** — `input tap/swipe/keyevent/text`, đủ phím.

## Build
- App: `Mobile\RemoteLink\build_apk.bat` (→ `RemoteLink.apk`, tự copy `__Result`).
- Viewer: `Desktop\RemoteLink\run.bat` (tạo venv + chạy thử) rồi `build_exe.bat` (→ `RemoteLink.exe`, tự copy `__Result`).

## Dùng
1. Cài `RemoteLink.apk` lên điện thoại. Mở app → bật các điều kiện còn vàng (Accessibility, bỏ tối ưu pin, thông báo).
2. Bấm **Bắt đầu chia sẻ màn hình** (MediaProjection sẽ hỏi đồng ý 1 lần). Màn chính hiện **IP + cổng**.
3. Trên PC chạy `RemoteLink.exe`:
   - **Stream**: nhập IP + cổng `5901` (mặc định).
   - **VNC**: nhập IP + cổng `5900`.
   - **ADB**: không cần IP/app — chỉ cần serial (hoặc để trống nếu 1 máy) và máy đã cắm/debug.
4. Kéo chuột = chạm/vuốt; bàn phím = gõ; nút Back/Home/Recents và ô "Gửi lệnh" ở thanh trên.

## Giao thức Stream (tóm tắt, khớp `net/StreamServer.kt` ↔ `Desktop/protocol.py`)
Frame: `[1 byte type][4 byte BE length][payload]`.
- server→client: `0x01` HELLO(`RLNK`+ver+w+h+authFlag) · `0x02` CHALLENGE(16) · `0x10` VIDEO_CONFIG(csd) · `0x11` VIDEO(`[1 flags][8 ptsUs][annexB]`).
- client→server: `0x80` AUTH(32=SHA-256(nonce+pw)) · `0x81` INPUT(subtype…) · `0x82` keepalive.

## Giới hạn đã biết (first pass)
- VNC server chỉ phục vụ **Raw 32bpp** (shift R0/G8/B16 = khớp RGBA); bỏ qua `SetPixelFormat` của client → viewer lạ ép 16bpp có thể sai màu. Viewer Desktop không ép nên OK.
- Stream ghi đồng bộ tới từng client → 1 client chậm làm chậm chung (chưa có hàng đợi riêng/queue).
- Root VNC dùng `screencap -p` mỗi khung → nặng, chỉ nên dùng FPS thấp; root nên ưu tiên Stream.
- Kéo liên tục (drag) qua Accessibility dùng continued-stroke (API 26+); máy cũ chỉ còn tap/swipe.
- `screenrecord` có giới hạn thời gian (~170s) → Stream tự mở lại; có thể gợn 1 nhịp khi nối lại.

## Checklist test (khi cắm thiết bị)
- [ ] Non-root: Start → đồng ý MediaProjection → Desktop (Stream) thấy hình, chuột/bàn phím điều khiển được.
- [ ] VNC: kết nối bằng 1 VNC viewer có sẵn tới `IP:5900` (không mật khẩu, rồi có mật khẩu).
- [ ] Accessibility: chạm/vuốt/kéo, Back/Home/Recents, gõ chữ vào ô nhập.
- [ ] Battery optimization + chạy nền khi tắt màn hình.
- [ ] Root (máy đã root): đổi Capture=Root, Input=Root → Stream chạy không cần hộp thoại.
- [ ] ADB mode trên Desktop: không mở app điện thoại, vẫn xem + điều khiển qua adb.

# SentryRoom

Phòng LAN: **chat, gửi file mọi định dạng (có % tiến trình), gọi thoại/video, và cảnh báo chuyển động**. Chỉ là một **server Python nhỏ phục vụ HTML thuần** — client vào phòng bằng cách quét **QR**. Nhẹ, đóng gói được thành 1 file .exe có icon.

## Chạy (cần Python 3)
```
pip install -r requirements.txt
python server.py          # mở http://localhost:3000 và tự bật trình duyệt
```
Hoặc bấm đúp `run.bat`. Máy khác cùng Wi-Fi: mở `http://<IP-LAN>:3000` (hoặc quét QR).

## Đóng gói .exe (không cần cài Python để chạy)
```
build.bat                 # PyInstaller onefile + icon
```
Ra **`dist\SentryRoom.exe`** (~15 MB, nhúng sẵn Python). Chạy trực tiếp là lên server + mở trình duyệt.

## Dùng
- Bấm **Mời (QR)** → chọn **địa chỉ máy chủ** trong danh sách rồi cho điện thoại quét.
  Danh sách xếp ưu tiên: **IP LAN nội bộ thật** (Wi-Fi/Ethernet) → IP nội bộ trên **card ảo**
  (Hyper-V/WSL/VMware/Docker…) → VPN/CGNAT → link-local → công khai → loopback.
  Lựa chọn được nhớ lại; nếu IP đó biến mất, server tự rơi về IP tốt nhất.
- 📎 gửi file (nhiều file cùng lúc, mỗi file có thanh %).
- 🎙 Thoại / 🎥 Video: gọi nhóm (WebRTC mesh).
- **Theo dõi chuyển động**: bật ở máy nào thì máy đó dùng camera phát hiện chuyển động.
  Chọn hành vi khi có chuyển động: **gửi ảnh vào chat**, **chỉ thông báo**, hoặc **cả hai**
  (chỉnh **độ nhạy** và **giãn cách** ngay tại máy đó).
- 🔔 **Thông báo**: tin nhắn mới, file mới, người vào/rời phòng, cuộc gọi đến, cảnh báo
  chuyển động. Dùng notification của hệ điều hành khi được cấp quyền, không thì hiện
  toast trong trang; số tin chưa đọc hiện ở tiêu đề tab.

## HTTPS (bắt buộc nếu muốn gọi thoại/video từ điện thoại)
Trình duyệt chỉ cho dùng camera/micro (`getUserMedia`) trên **secure context**: `https://…`
hoặc `localhost`. Vào bằng `http://192.168.x.x` thì `navigator.mediaDevices` là `undefined`
→ lỗi *Cannot read properties of undefined (reading 'getUserMedia')*.

Vì vậy server tự tạo **chứng chỉ tự ký** (SAN gồm mọi IP nội bộ, cache ở
`%TEMP%\sentryroom_cert`) và lắng nghe thêm ở cổng **`PORT + 443`** (mặc định `3443`).
QR/link mời sẽ trỏ vào `https://`. Lần đầu điện thoại sẽ cảnh báo chứng chỉ → **Nâng cao →
Tiếp tục**. Cần `pip install cryptography`; thiếu thì server vẫn chạy nhưng chỉ có http.

Biến môi trường: `PORT`, `HTTPS_PORT`, `NO_HTTPS=1` (tắt https), `NO_OPEN=1` (không tự mở
trình duyệt). Nếu điện thoại không vào được, mở cổng đó trong Windows Firewall.

## Tự kết nối lại
Ba tầng đều có retry riêng, hiển thị bằng đèn ● cạnh tên phòng (xanh = đã kết nối,
vàng nháy = đang thử lại, đỏ = mất kết nối):

1. **Socket.IO**: `reconnectionAttempts: Infinity`, delay 0.6s→5s; bắt cả event `online`
   của trình duyệt. Khi mất kết nối, mesh WebRTC bị dọn (peer id chính là socket id nên
   sau reconnect ai cũng có id mới) nhưng **mic/camera giữ nguyên** → vào lại không phải
   xin quyền lần nữa, cuộc gọi tự dựng lại từ danh sách peer mới.
2. **WebRTC mỗi peer**: `disconnected` → chờ 2.5s (thường tự khỏi) → **ICE restart** với
   backoff 1s/2s/4s/8s, tối đa 5 lần → vẫn không được thì **dựng lại `RTCPeerConnection`**
   từ đầu. Không còn xoá peer khi `failed` như trước. Tile của peer mờ đi và ghi
   "kết nối lại n/5" trong lúc thử.
3. **Mic/camera**: mọi track được theo dõi `ended`/`mute`, cộng với watchdog 5s và kiểm tra
   lúc tab quay lại foreground (điện thoại khoá màn hình thường làm chết track). Track chết
   → mở lại thiết bị rồi `replaceTrack()` vào mọi peer connection, backoff 1s→15s nếu thiết
   bị đang bị app khác chiếm.

## Camera dùng chung
Webcam trên Windows thường chỉ mở được **một lần**. Nếu đang bật phát hiện chuyển động rồi
gọi video (hoặc ngược lại), client **dùng chung một stream camera** thay vì mở lần hai —
trước đây lần mở thứ hai cho ra khung đen. Camera chỉ tắt khi cả hai tính năng đều tắt.

> Cùng mạng LAN/Wi-Fi. Voice/video có STUN dự phòng nên cần Internet nếu client bị chặn kết nối trực tiếp.

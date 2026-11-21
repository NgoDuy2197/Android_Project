# ScreenGrab — Desktop (Windows)

Chụp ảnh & quay màn hình PC. Chi tiết đầy đủ (cả app Android) ở `../../Mobile/ScreenGrab/README.md`.

- `run.bat` — tạo `.venv`, cài deps, chạy GUI.
- `build_exe.bat` — đóng gói `dist\ScreenGrab.exe` và copy về `WebRTC\__Result`.

## Chức năng
- **Chụp ảnh** (mss) — toàn màn hình hoặc vùng chọn → `~/Pictures/ScreenGrab`.
- **Quay video** (ffmpeg `gdigrab`, bundled qua `imageio-ffmpeg`) — toàn màn hình / vùng chọn → `~/Videos/ScreenGrab`.
- **Âm thanh**: chọn thiết bị **Mic** và/hoặc **Âm thanh máy** (DirectShow). Trộn bằng `amix` khi chọn cả hai.
- **Thử gỡ chặn capture**: `SetWindowDisplayAffinity(hwnd, WDA_NONE)` trên các cửa sổ.

## Âm thanh máy (loopback) trên Windows
Windows không cho ffmpeg lấy loopback trực tiếp. Cần một **thiết bị loopback**:
- Bật **Stereo Mix** (Control Panel → Sound → Recording → chuột phải → Show Disabled Devices → Enable), **hoặc**
- Cài **VB-Audio Cable** / tương tự, đặt làm thiết bị nghe.
Sau đó nó xuất hiện trong ô “Âm thanh máy”.

## Giới hạn (first pass) — [Unverified], chưa chạy thực tế
- `gdigrab` bị **đen** với cửa sổ đặt `WDA_EXCLUDEFROMCAPTURE` (vd Telegram Desktop) — nút “gỡ chặn” **cố thử** nhưng
  `SetWindowDisplayAffinity` thường **bị từ chối cross-process**; và **DRM phần cứng** (Udemy Widevine L1) không thể vượt bằng phần mềm.
- Muốn quay chắc các cửa sổ ẩn hơn: cần Windows.Graphics.Capture (gói `windows-capture`) — có thể bổ sung sau.
- Chỉ xử lý **màn hình chính**; đa màn hình cần chỉnh offset.
- `libx264` lấy từ bản ffmpeg của `imageio-ffmpeg` (bản GPL có x264); nếu bản không có, đổi `-c:v` sang `mpeg4`.

## Files
`app.py` (GUI + overlay vùng) · `recorder.py` (ffmpeg) · `screenshot.py` (mss) ·
`ffmpegutil.py` (ffmpeg + liệt kê thiết bị âm thanh) · `winbypass.py` (gỡ WDA) · `make_icon.py`.

# ScreenGrab

Chụp ảnh và **quay màn hình** — **toàn màn hình hoặc vùng chọn**, kèm **âm thanh máy** và/hoặc **mic**.
Một cặp project: **`Mobile/ScreenGrab`** (app Android → `ScreenGrab.apk`) và
**`Desktop/ScreenGrab`** (app Windows Python/PySide6 → `ScreenGrab.exe`). Mỗi app quay màn hình **máy của chính nó**.
Cả hai copy về `WebRTC/__Result`.

> ⚠️ Trạng thái: **code hoàn chỉnh, build OK, CHƯA test trên thiết bị thật.**
> Các nhận định về hành vi Android/Windows bên dưới là [Inference] theo tài liệu API, chưa chạy kiểm chứng.

## Android (`com.dsoft.screengrab`)
- **Chụp ảnh / Quay video**, toàn màn hình hoặc **vùng chọn** (kéo khung trong màn “Chọn vùng”).
- **Âm thanh máy** (tiếng đang phát) — Android 10+ qua AudioPlaybackCapture; **mic**; bật/tắt từng cái; trộn khi bật cả hai.
- **2 chế độ quay** (chọn trong app):
  - **Không root** — MediaProjection. Hỏi đồng ý **1 lần/phiên** (giữ phiên trong foreground service). Có nút Chụp/Quay trên thanh thông báo để thao tác khi đang ở app khác.
  - **Root** — `screencap` (ảnh) / `screenrecord` (video, **toàn màn hình, không tiếng** — giới hạn của `screenrecord`).
- **Quay được app chặn capture (Telegram, Udemy)** nhờ **module LSPosed** gỡ `FLAG_SECURE`. Máy cần **đã root + cài LSPosed**. Ba cách bật (dễ → khó):
  1. **Một chạm (khuyên dùng):** mở ScreenGrab → nút **“⚡ Tự bật hook cho MỌI app (root)”**. App tự gọi **LSPosed Vector CLI** (`/data/adb/lspd/cli`) để enable module + set scope. *Chỉ chạy với bản **LSPosed Vector** (có CLI); bật xong phải **mở lại** app muốn quay.*
  2. **Thủ công (LSPosed bản gốc, không CLI):** LSPosed Manager → Modules → bật **ScreenGrab** → Scope tick app (Telegram/Udemy…) + **System Framework** → **ép dừng** app đó.
  3. **Dòng lệnh:** `su -c "/data/adb/lspd/cli modules enable com.dsoft.screengrab"` rồi `su -c "/data/adb/lspd/cli scope set com.dsoft.screengrab <pkg>/0 android/0"`.
  - Sau đó quay bằng chế độ **Không root** (MediaProjection) như bình thường. Thẻ “Hook LSPosed” ở màn chính báo đã hoạt động chưa.
  - [Unverified] Hook chỉ gỡ cờ ở tầng cửa sổ; **DRM phần cứng** (vd Widevine L1 của một số video) nằm dưới tầng view, hook **không** vượt được.

### Pipeline (kỹ thuật)
- Video: `VirtualDisplay → GlPipeline (crop bằng OpenGL) → MediaCodec H.264 → MediaMuxer (mp4)`.
  Crop toạ độ texture nên **vùng chọn và toàn màn hình dùng chung một đường**, không copy thừa.
- Âm thanh: `AudioRecord` (mic và/hoặc AudioPlaybackCapture) → trộn PCM → AAC → cùng muxer.
- Ảnh: `ImageReader` một khung → cắt theo vùng → PNG (root: `screencap -p`).
- Lưu vào **Thư viện** (MediaStore: Pictures/ScreenGrab, Movies/ScreenGrab).

## Build
- App: `Mobile\ScreenGrab\build_apk.bat` (→ `ScreenGrab.apk`, tự copy `__Result`).
- Viewer PC: `Desktop\ScreenGrab\run.bat` (tạo venv + chạy thử) rồi `build_exe.bat` (→ `ScreenGrab.exe`, tự copy `__Result`).

## Windows (`Desktop/ScreenGrab`)
- Chụp ảnh (mss) + quay (ffmpeg bundled qua `imageio-ffmpeg`) — toàn màn hình / vùng chọn.
- Âm thanh: chọn **Mic** và/hoặc **Âm thanh máy** từ danh sách thiết bị DirectShow (loopback cần **Stereo Mix**/VB-Cable bật).
- Nút “**Thử gỡ chặn capture**”: gọi `SetWindowDisplayAffinity(WDA_NONE)` lên các cửa sổ (best-effort).
  [Unverified] Thường thất bại cross-process và không vượt DRM phần cứng — xem README Desktop.

## Bắt & tải video (sniffer) — mới
Bật trong app (mục **BẮT & TẢI VIDEO**) hoặc toggle **🎬 Bắt video** trong nút nổi. Khi bật, app chạy một
**MITM proxy nội bộ** (port mặc định 8899) soi luồng HTTP(S) của mọi app/Chrome, nhận diện video và liệt kê ở
màn **Video đã bắt**; bấm **Tải** để lưu.
- **Nhận diện:** HLS (`.m3u8`), DASH (`.mpd`), và file trực tiếp (`video/*`, `audio/*`, `.mp4`…). Bỏ qua từng
  đoạn `.ts/.m4s` (chỉ bắt playlist/ file hoàn chỉnh).
- **Tải:** file trực tiếp → tải thẳng; HLS → tự chọn variant bitrate cao nhất, tải hết đoạn (giải mã AES‑128 nếu
  có), **ghép thành 1 video** (`.ts` nối trực tiếp, hoặc `.mp4` nếu fMP4). DASH: best‑effort (SegmentTemplate
  `$Number$` / SegmentList-BaseURL). Lưu vào **Movies/ScreenGrab** (hoặc Music cho audio).
- **Định tuyến:**
  - **Root (nên dùng):** tự cài CA ScreenGrab vào kho hệ thống + `settings put global http_proxy <LAN_IP>:8899`.
    Tắt sniffer sẽ `fullRestore` (gỡ proxy + tháo CA). App pinning: bỏ pin bằng LSPosed sẵn có.
  - **VPN (không root, [Unverified] thử nghiệm):** `VpnService.setHttpProxy` (Android 10+). Chưa chắc ăn mọi máy
    vì loopback theo từng app — ưu tiên Root.
- **[Inference] Giới hạn:** HTTPS phải MITM mới thấy URL; app có **cert pinning** chặn MITM (xử lý bằng LSPosed
  root). **DRM (Widevine)** không tải được. Live stream / DASH động có thể chưa đủ đoạn.

## Giới hạn đã biết (first pass)
- Root record: `screenrecord` không cắt vùng & không có tiếng → chế độ root chỉ quay **toàn màn hình, không âm**.
- Chụp ảnh ở chế độ Không-root sẽ bắt đúng nội dung màn hình tại thời điểm bấm — dùng nút trên **thanh thông báo** để chụp app khác.
- Hướng dọc/ngang của crop OpenGL cần **xác minh trên thiết bị** (đã theo công thức grafika để giữ đúng chiều).
- AudioPlaybackCapture chỉ bắt được app **cho phép** bị ghi (một số app đặt `setAllowedCapturePolicy` chặn) — [Inference].

## Checklist test (khi cắm thiết bị)
- [ ] Không-root: Chụp ảnh / Quay toàn màn hình → mở trong Thư viện, có hình.
- [ ] Vùng chọn: kéo khung → quay → video đúng vùng, đúng chiều.
- [ ] Âm thanh máy (mở nhạc) + mic → video có tiếng, trộn ổn.
- [ ] LSPosed: bật module + scope Telegram/Udemy + force stop → quay **không còn đen**.
- [ ] Root mode: Chụp (`screencap`) + Quay (`screenrecord`) chạy không cần hộp thoại.
- [ ] Thanh thông báo: Chụp/Dừng khi đang ở app khác.
- [ ] Sniffer (Root): bật → phát video trong Chrome/Udemy → link hiện ở **Video đã bắt** → **Tải** ra file xem được.
- [ ] HLS có mã hoá AES‑128 → tải + ghép xong phát bình thường (không lỗi giải mã).
- [ ] Tắt sniffer → mạng trở lại bình thường (proxy gỡ, CA tháo).

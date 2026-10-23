# ProxySpy — Checklist dùng nhanh

## A. Chạy proxy (trên PC)
1. Chạy `run.bat` (hoặc `ProxySpy.exe`). Thấy banner → proxy ở **:8080**, dashboard **:8081**.
2. Mở dashboard: `http://127.0.0.1:8081`. (Server tắt/đổi code → chạy lại `run.bat`.)

## B. Nối điện thoại
**Cách 1 — máy cắm USB (chắc nhất, bỏ qua Wi-Fi/firewall):**
```
adb reverse tcp:8080 tcp:8080
adb shell settings put global http_proxy 127.0.0.1:8080      # MIUI/Redmi cho phép
# Máy chặn (ColorOS…): đặt tay Wi-Fi proxy, hoặc có root:
# adb shell su -c "settings put global http_proxy 127.0.0.1:8080"
```
**Cách 2 — qua Wi-Fi:** điện thoại cùng mạng → Wi-Fi > Proxy > Thủ công = `IP_PC:8080`
(bấm nút **▣ QR** trên dashboard để quét; `run.bat` đã tự mở firewall).

## C. Cài CA (bắt buộc để đọc HTTPS)
- **App thường / trình duyệt (tin user-CA):** nút **Tải CA** hoặc **▣ QR** trên dashboard, hoặc mở
  `http://mitm.it` trên máy → cài: *Cài đặt → Bảo mật → Cài chứng chỉ → Chứng chỉ CA*.
- **App targetSdk ≥ 24, không debuggable (vd `com.example.app`):** chỉ tin **CA hệ thống** ⇒ **cần root**.
  **Bấm phát ăn ngay:**
  1. Magisk → cấp quyền **Shell (adb)** (tới khi `adb shell su -c id` ra `uid=0(root)`).
  2. Chạy **`setup_root_ca.bat`** → tự: tạo cert theo hash → đặt proxy/USB → cài CA hệ thống → mở lại app.
     - Soi app khác: `setup_root_ca.bat <package>`.
  3. Mở app trên máy → request hiện ở dashboard.
  - Thủ công / chi tiết từng bước: xem comment trong `setup_root_ca.bat` + `root_system_ca.sh`.
  - Bind-mount mất sau **reboot** → chạy lại `setup_root_ca.bat`.

## D. App vẫn đứt sau khi đã tin CA = cert pinning
- Máy root → Frida: `frida -U -f <package> -l unpin.js`, hoặc objection `android sslpinning disable`.

## E. Gỡ sạch khi xong
```
adb shell settings put global http_proxy :0
adb reverse --remove-all
```
(Gỡ CA / tháo bind-mount nếu đã cài CA hệ thống: reboot là sạch.)

## Lỗi hay gặp
| Triệu chứng | Nguyên nhân / xử lý |
|---|---|
| Dashboard trắng, app "unexpected end of stream" | ProxySpy chưa chạy → chạy lại `run.bat` |
| `Trust anchor for certification path not found` | CA chưa được tin → cài CA; app targetSdk cao cần **CA hệ thống (root)** |
| App vẫn đứt dù đã tin CA | Cert pinning → dùng **Frida** gỡ pin |
| YouTube / app Google không vào | Pin cứng — bỏ qua, không MITM được |
| `svc wifi enable` không bật được Wi-Fi | MIUI chặn qua adb → bật tay hoặc **reboot** |

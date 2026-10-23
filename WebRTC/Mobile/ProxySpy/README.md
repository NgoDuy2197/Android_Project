# ProxySpy (Android) — proxy MITM chạy trên điện thoại

App mở một **proxy** ngay trên điện thoại; máy khác trỏ Wi-Fi proxy vào đây, ProxySpy giải mã
HTTPS và hiển thị mọi request/header/payload — như bản PC, nhưng gọn trong 1 app.

Engine: terminate TLS phía client bằng **cert động (BouncyCastle)** → forward bằng **OkHttp**.

## Kịch bản khuyến nghị (2 máy)
- **Máy A** (thường, không cần root): cài & mở app này → **Bật proxy**. App hiện `IP_A : 8080`.
- **Máy B** (ĐÃ ROOT): máy cần soi (app đích bất kỳ). B trỏ Wi-Fi proxy vào `IP_A:8080`.
  Vì B có root → cài **CA của A thành CA hệ thống trên B** → app targetSdk cao vẫn tin → bắt được hết.
- A, B, PC **cùng một Wi-Fi**.

### Làm trên B (tự động bằng PC — B cắm USB + đã cấp root cho shell)
```
cd WebRTC\Desktop\ProxySpy
setup_client_root.bat <IP_cua_A>[:8080]  [package]
```
Bat sẽ: lấy CA từ app A (qua proxy) → đặt proxy B về A → cài CA hệ thống trên B → mở lại app.
Trả B về bình thường khi xong: `go_bo.bat`.

### Hoặc cài CA thủ công
- Máy khách đặt proxy = `IP_A:8080`, mở `http://proxyspy.ca` → tải CA → cài
  (App thường tin user-CA; app targetSdk ≥ 24 không debuggable thì phải là **CA hệ thống / root**).

## Dùng 1 máy (app tự soi chính nó)
Đặt Wi-Fi proxy của chính máy = `127.0.0.1:8080`, cài CA (nút **Cài CA** trong app).

## Giới hạn
- Engine HTTP/1.1 (chưa HTTP/2, chưa WebSocket).
- App **pin cert** vẫn đứt dù đã tin CA.
- Proxy chạy nền tốn pin; nên cắm sạc khi soi lâu.

## Build
`build_apk.bat` → `ProxySpy.apk` (copy sang `WebRTC\__Result`).

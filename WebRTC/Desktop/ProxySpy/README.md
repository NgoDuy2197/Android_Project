# ProxySpy — Proxy gỡ lỗi HTTPS (Dsoft)

Chạy một **proxy MITM** trên máy tính. Điện thoại khác trỏ Wi-Fi proxy vào máy này,
ProxySpy giải mã lưu lượng (sau khi điện thoại tin chứng chỉ CA) và hiển thị **mọi
request, header và payload** trên một dashboard web chạy ngay trên máy.

Nền tảng: [mitmproxy](https://mitmproxy.org) (sinh cert, HTTP/2, WebSocket) + dashboard tự viết.

---

## Chạy nhanh (Windows)

**Cách 1 — bản đóng gói (không cần cài gì):** chạy `ProxySpy.exe` trong `WebRTC\__Result`
(hoặc `dist\ProxySpy.exe` sau khi build). Lưu ý: lần đầu cần tự mở firewall nếu Windows hỏi.

**Cách 2 — từ mã nguồn:**
```bat
run.bat
```

`run.bat` sẽ: (1) **kiểm tra & mở port firewall** cho proxy + dashboard (hiện UAC xin quyền Admin
để máy cùng mạng kết nối được), (2) tạo `.venv` + cài gói lần đầu. Sau đó:

- Proxy lắng nghe ở **cổng 8080** trên mọi IP của máy.
- Dashboard mở ở **http://&lt;IP-máy&gt;:8081** — mặc định cho **máy cùng mạng LAN** xem/quét QR.
  Muốn khoá chỉ về máy này: `run.bat --dash-host 127.0.0.1`.

Đóng gói lại thành `.exe`: chạy `build_exe.bat` (cần đã chạy `run.bat` 1 lần). File ra ở
`dist\ProxySpy.exe` và được copy sang `WebRTC\__Result`.

Tuỳ chọn cổng:

```bat
run.bat --port 9090 --dash-port 9091
```

Chạy thủ công (nếu đã có Python 3.9+):

```bat
python -m venv .venv
.venv\Scripts\python -m pip install -r requirements.txt
.venv\Scripts\python app.py
```

---

## Nối điện thoại qua proxy

1. Điện thoại **cùng Wi-Fi** với máy tính.
2. Wi-Fi đang kết nối → **Proxy** → **Thủ công**: Host = IP máy tính (xem ở dashboard/console), Port = `8080`.
3. **Cài chứng chỉ CA** cho điện thoại:
   - Mở trình duyệt điện thoại vào `http://mitm.it` (trang cài cert của mitmproxy), **hoặc**
   - Bấm **Tải CA** trên dashboard để lấy `ProxySpy-CA.cer`, rồi cài ở
     **Cài đặt → Bảo mật → Cài chứng chỉ → Chứng chỉ CA**.
4. Mở app/trình duyệt trên điện thoại → request hiện lên dashboard theo thời gian thực.
5. Xong việc: **tắt proxy** trên điện thoại, gỡ CA nếu cần.

---

## Giới hạn cần biết (thành thật)

- **Android 7+**: app (nhất là bản release) chỉ tin CA do người dùng cài nếu nó khai báo
  `networkSecurityConfig`. Nhiều app **không tin** → không giải mã được payload của app đó.
- **Certificate pinning**: app ghim cert riêng → không đọc được nếu không can thiệp sâu
  (root / Frida). Trình duyệt và app không pin thì xem được đầy đủ.
- Vì vậy *"thấy mọi payload"* đúng với HTTP và HTTPS của trình duyệt / app chịu cài CA & không pin,
  **không đảm bảo 100% mọi app**.
- Chỉ dùng với **thiết bị và app của chính bạn** (gỡ lỗi hợp pháp).

---

## Dashboard

- Bảng request thời gian thực: method, host, đường dẫn, status, kiểu, kích thước, thời lượng.
- Lọc theo method và ô tìm kiếm (host/đường dẫn).
- Bấm 1 dòng → xem request/response đầy đủ: headers, body (JSON tự format, nhị phân hiện base64).
- **Tạm dừng / Tiếp tục** bắt gói, **Xoá**, **Tải CA**, **▣ QR**, **Hướng dẫn**.
- **▣ QR**: hiện mã QR của link dashboard (theo IP LAN) để điện thoại cùng Wi-Fi quét là mở được
  (tải CA / xem hướng dẫn) — không cần gõ địa chỉ.

Mặc định dashboard bind `0.0.0.0` để máy cùng mạng xem được và QR dùng được.
Dữ liệu bắt được (có thể chứa mật khẩu) sẽ **hiện trong mạng LAN** — muốn khoá về máy này
dùng `--dash-host 127.0.0.1`.

---

## Cấu trúc

| File | Vai trò |
|------|---------|
| `app.py` | Khởi động mitmproxy + dashboard, in địa chỉ proxy/CA |
| `capture.py` | Addon mitmproxy + kho flow (thread-safe), trích body/header |
| `server.py` | HTTP server phục vụ dashboard + API (stdlib) |
| `dashboard.html` | Giao diện web (1 file, không cần mạng) + nút QR |
| `run.bat` | Mở firewall, tạo venv, cài gói, chạy |
| `build_exe.bat` | Đóng gói `dist\ProxySpy.exe` (PyInstaller) + copy sang `__Result` |

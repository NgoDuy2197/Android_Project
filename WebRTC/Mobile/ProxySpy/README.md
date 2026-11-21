# ProxySpy (Android) — soi & sửa API ngay trên điện thoại

Một app, **hai chế độ bắt**. Bật cái nào cũng được, bật cả hai cũng được — flow đổ chung một danh
sách, dùng chung một bộ **Rules**.

| | **Proxy (MITM)** | **Tự soi (hook)** |
|---|---|---|
| Cách chạy | máy khác trỏ Wi-Fi proxy vào máy này | hook thẳng vào tiến trình app đích trên chính máy này |
| Cần cài CA | Có | **Không** |
| App pin certificate | Đứt | **Vẫn bắt được** (nằm *bên trong* lớp TLS) |
| WebSocket | Không | **Có, cả 2 chiều** |
| HTTP/2 | Không | **Có** (đọc ở tầng OkHttp, đã giải mã sẵn) |
| Chọn theo app | Không | **Có** |
| Yêu cầu | 2 máy cùng Wi-Fi | **Root + LSPosed** (APK này *cũng là* module Xposed) |
| Sửa request/response | Có | **Có + breakpoint dừng tay** |

---

# A. Chế độ TỰ SOI (khuyên dùng khi soi app của chính mình)

## 1. Yêu cầu
- Android 7.0+ (API 24), **đã root**.
- **Magisk/KernelSU + Zygisk + LSPosed**.
- App cần soi phải có quyền `INTERNET` (hook nói chuyện với app qua socket loopback).

## 2. Bật
1. Cài `ProxySpy.apk`.
2. **LSPosed Manager → Modules → ProxySpy → bật**.
3. **Scope**: tick **app cần soi** và tick luôn **ProxySpy** (để app biết module đã bật — dòng trạng
   thái sẽ hiện `module LSPosed BẬT`).
4. Mở ProxySpy → **Bật tự soi** (dựng Hub ở `127.0.0.1:48732`).
5. **Chọn app** → tick app đích. Giữ lâu một app trong danh sách → **Ép dừng** (`su -c am force-stop`).
6. Mở lại app đích. Xong — mọi request/frame đổ về danh sách.

> Hook chỉ gắn khi app **khởi động lại**. App không được chọn (hoặc Hub đang tắt) thì mọi hook thoát
> ngay ở một phép đọc biến, không ảnh hưởng tốc độ app.

## 3. Hai tầng bắt
| Tầng | Bắt được gì | Sửa được không |
|---|---|---|
| **OkHttp** (mặc định) | Retrofit, OkHttp, Ktor (engine OkHttp), Coil/Glide/Picasso, Volley-qua-OkHttp; HTTP/1.1 + HTTP/2 đã giải mã; WebSocket của OkHttp | **Có** — mọi rule + breakpoint |
| **Thô (socket)** — bật trong *Chọn app* | traffic Java không qua OkHttp (`HttpURLConnection`, socket tự viết); chỉ HTTP/1.1 + frame WS | **Không** (chỉ ghi lại) |

## 4. Breakpoint — dừng lại sửa tay
Nút **Breakpoint**: bật *dừng request* / *dừng response* / *dừng frame WS*, kèm ô lọc URL. Có thể bật
riêng cho từng rule (ô `dừng req` / `dừng res` trong phần **Nâng cao**).

Khi một call bị dừng: app **đứng im tại call đó**, ProxySpy hiện banner + thông báo heads-up. Chạm
vào → sửa URL / headers / status / body → **Gửi tiếp**, **Nguyên bản**, hoặc **Bỏ**. Không trả lời
trong **2 phút** thì call tự đi tiếp nguyên bản.

## 5. Giới hạn (nói thẳng)
- App **release bật R8/obfuscate** → tên lớp `okhttp3.*` bị đổi → tầng OkHttp không tìm thấy. Soi
  **bản debug của chính bạn** là trúng nhất; với bản release, dùng tầng thô hoặc chế độ proxy.
- TLS chạy ở **native** (Cronet/WebView, Flutter, gRPC native, NDK socket) không đi qua stream Java
  → không thấy.
- Tầng thô **không giải mã HTTP/2** (không có HPACK) — chỉ báo "dùng tầng OkHttp để xem".
- Body kiểu **duplex / one-shot / `text/event-stream`** và body **> 8 MB** được cho đi thẳng, không
  buffer (nếu không sẽ treo app hoặc vỡ heap).
- Hub chỉ `bind` vào **127.0.0.1**, không ra ngoài máy; nhưng khi đang bật, app nào được LSPosed nạp
  module vào cũng nối vào được → **tắt tự soi khi không dùng**.

---

# B. Chế độ PROXY (soi máy khác, không cần LSPosed)

App mở một **proxy** ngay trên điện thoại; máy khác trỏ Wi-Fi proxy vào đây, ProxySpy giải mã HTTPS
và hiển thị mọi request/header/payload.

Engine: terminate TLS phía client bằng **cert động (BouncyCastle)** → forward bằng **OkHttp**.

## Kịch bản khuyến nghị (2 máy)
- **Máy A** (thường, không cần root): cài & mở app này → **Bật proxy**. App hiện `IP_A : 8080`.
- **Máy B** (ĐÃ ROOT): máy cần soi. B trỏ Wi-Fi proxy vào `IP_A:8080`.
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
- Engine HTTP/1.1 (chưa HTTP/2, chưa WebSocket) — những thứ đó dùng **chế độ tự soi**.
- App **pin cert** vẫn đứt dù đã tin CA — dùng **chế độ tự soi**.
- Proxy chạy nền tốn pin; nên cắm sạc khi soi lâu.

---

# C. Rules — dùng chung cho cả hai chế độ

`⚙ Rules` → `+ Thêm`. Khớp theo **URL chứa …** hoặc **`re:regex`**, lọc thêm theo method.
Bấm **Lưu & áp dụng** — rule được đẩy xuống app đang hook **ngay**, không cần khởi động lại app.

**Luôn hiện**
| Ô | Tác dụng |
|---|---|
| **mock** | trả luôn body đã nhập, **không gọi server** |
| status | ép `401`/`500` để test nhánh lỗi |
| body / find → replace | thay toàn bộ, hoặc thay một chuỗi trong response |
| payload thay thế / merge JSON / find → replace | sửa dữ liệu gửi lên |

**Nâng cao** (bấm *Nâng cao ▾*)
| Ô | Tác dụng | Chế độ |
|---|---|---|
| đổi URL tìm → thay | bẻ call sang server khác (`api.prod` → `api.staging`) | cả hai |
| header gửi lên / trả về | `Authorization: Bearer <token test>` | cả hai |
| merge JSON vào body trả về | giữ response thật, chỉ đè vài field (`{"isVip":true}`) | cả hai |
| trễ (ms) | xem spinner / timeout | cả hai |
| ép lỗi mạng | ném lỗi với thông báo của bạn | cả hai |
| dừng req / dừng res | breakpoint cho riêng rule này | tự soi |
| WS + frame gửi/nhận/bỏ + sửa frame | rule áp cho frame WebSocket | tự soi |

---

# D. Build & cấu trúc

```
build_apk.bat            :: ra ProxySpy.apk (copy sang WebRTC\__Result)
build_apk.bat install    :: build + adb install
```

```
app/src/main/java/com/dsoft/proxyspy/
  Wire.kt             giao thức 1 dòng JSON giữa hook và Hub; đóng gói body
  Rules.kt            model rule + matching + áp dụng (dùng chung 2 chế độ) + RuleStore
  FlowStore.kt        Flow/Body + ring buffer mà cả proxy lẫn hook cùng đổ vào
  ProxyEngine.kt      chế độ proxy: terminate TLS, forward, áp rule
  ProxyService.kt     foreground service của proxy
  Hub.kt              chế độ tự soi: server loopback, nhận capture, quản lý breakpoint
  HookService.kt      foreground service giữ Hub sống
  hook/HookEntry.kt   entry point LSPosed (assets/xposed_init)
  hook/Bridge.kt      phía hook: socket về Hub, nhận config, hỏi verdict khi breakpoint
  hook/OkApi.kt       phản chiếu OkHttp/Okio *của app đích* (OkHttp 3 và 4)
  hook/OkHttpHook.kt  chèn Interceptor động → bắt + sửa HTTP
  hook/WsHook.kt      hook RealWebSocket → bắt + sửa frame
  hook/RawHook.kt     tee stream Conscrypt → parse HTTP/1.1 + frame WS (chỉ đọc)
  MainActivity / DetailActivity / RulesActivity / AppPickerActivity / BreakActivity / ClientActivity
xposed-api/           stub compileOnly của de.robv.android.xposed.* (KHÔNG đóng vào APK)
```

`xposed-api` chỉ để biên dịch; lúc chạy LSPosed cung cấp lớp thật. Vì vậy app **không bật minify**
(hook được nạp theo tên lớp từ tiến trình khác).

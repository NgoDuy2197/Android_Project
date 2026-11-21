# AutoTap — Tự động chạm / Auto-clicker

AutoTap là ứng dụng Android gốc (Kotlin, Android Views) giúp tự động hoá thao tác
trên màn hình: chạm, giữ, vuốt, chờ, chạm theo chữ (OCR), và chạy lại nhiều lần.
Bạn tạo "kịch bản" gồm các bước, rồi cho AutoTap tự thực hiện, hoặc kích hoạt từ
xa qua một API HTTP nhỏ gọn.

> Giao diện kiểu iOS trong suốt, toàn bộ tiếng Việt.

---

## Tính năng chính

- **Bộ chạy (AccessibilityService):** thực hiện chạm/giữ/vuốt thật qua
  `dispatchGesture`, cùng các phím toàn cục Back / Home / Ứng dụng gần đây.
- **Kịch bản:** danh sách các bước có thứ tự, số lần lặp, thời gian chờ mặc định.
  Lưu dưới dạng JSON trong bộ nhớ ứng dụng (ghi nguyên tử: ghi file tạm rồi đổi tên).
- **Các loại bước:** `TAP`, `LONG_PRESS`, `SWIPE`, `WAIT`, `TAP_TEXT` (OCR),
  `INPUT` (nhập chữ vào ô đang chọn), `BACK`, `HOME`, `RECENTS`.
- **Tham số động:** các ô chữ (ví dụ nội dung của `TAP_TEXT`, `INPUT`) có thể chứa
  `{{ten}}`; khi chạy với tham số, AutoTap sẽ thay thế giá trị tương ứng.
- **Chạm theo chữ (OCR):** chụp màn hình qua MediaProjection rồi dùng ML Kit nhận
  dạng chữ (bộ Latin), tìm chữ cần chạm và bấm vào giữa vùng chữ đó.
- **Ghi thao tác:** lớp phủ toàn màn hình ghi lại các lần chạm/vuốt của bạn thành
  các bước (kèm khoảng nghỉ thật dưới dạng bước `WAIT`). Có thanh điều khiển nổi
  kéo được: Ghi / Tạm dừng / Dừng / Hoàn tác và bộ đếm bước.
- **Chọn điểm:** lớp phủ đơn giản để lấy một toạ độ cho một bước.
- **Trình sửa thủ công:** thêm/sửa/xoá/đổi thứ tự các bước, sửa tên, số lần lặp,
  thời gian chờ; nút "Chạy thử".
- **Tự động bấm chữ (chạy nền):** theo dõi màn hình, thấy chữ đã cấu hình (ví dụ
  "Skip") là tự bấm — **không cần OCR, không cần quyền ghi màn hình**. Xem mục riêng bên dưới.
- **API server:** server HTTP nhỏ (socket thuần) để chạy kịch bản từ máy khác.
- **Chạy trên Android TV:** app lên launcher tivi, điều khiển bằng remote được.

---

## Cài đặt & build

- Windows 11, build bằng `build_apk.bat` (gọi Gradle wrapper 8.9).
- Yêu cầu `JAVA_HOME` trỏ tới JDK của Android Studio (JBR):
  `C:\Program Files\Android\Android Studio\jbr`.
- Android SDK: `C:/Users/<user>/AppData/Local/Android/Sdk`.

```bat
build_apk.bat            :: tạo APK debug, chép ra AutoTap.apk và WebRTC\__Result
build_apk.bat install    :: build + adb install
build_apk.bat clean      :: dọn output
```

APK sau khi build in dòng `[build] SUCCESS` và nằm tại `AutoTap.apk`.

- `applicationId` / package: `com.dsoft.autotap`
- minSdk 24, targetSdk/compileSdk 34, Java/Kotlin 17
- ABI: `arm64-v8a`, `armeabi-v7a`

---

## Cách dùng

### 1) Bật dịch vụ Trợ năng (bắt buộc để chạy)
Mở AutoTap → thẻ **Thiết lập** → *Dịch vụ Trợ năng* → bật **AutoTap** trong màn hình
Trợ năng của hệ thống. Không bật thì AutoTap không thể tự chạm.

### 2) (Tuỳ chọn) Bật OCR cho bước "Chạm theo chữ"
Thẻ **Thiết lập** → *Đọc màn hình OCR* → cấp quyền ghi màn hình (chọn "Toàn bộ
màn hình"). Khi bật, bước `TAP_TEXT` mới hoạt động.

### 3) Tạo kịch bản
Chạm **＋** → mở trình sửa. Thêm bước thủ công, hoặc dùng **Ghi thao tác**.

> **Lưu ý khi Ghi thao tác:** lớp phủ ghi hình *chặn* chạm, nên ứng dụng bên dưới
> **không phản hồi** trong lúc ghi — bạn chỉ đang *chỉ ra* vị trí chạm/vuốt. Khi
> **chạy**, AutoTap mới thực sự bấm qua dịch vụ Trợ năng. Lớp phủ để hơi tối giúp
> bạn nhìn thấy ứng dụng phía dưới mà ngắm. Chỉnh độ tối trong **Cài đặt**.

### 4) Chạy
Nút ▶ ở mỗi kịch bản, hoặc "Chạy thử" trong trình sửa. AutoTap sẽ thu nhỏ và mở
ứng dụng đích để thực hiện. Chạm lại ▶ (hoặc "■ Dừng") để dừng.

---

## API server

Thẻ **API server** → bật. Địa chỉ dạng `http://IP:8900` (IP Wi-Fi/hotspot).
Cấu hình cổng, tài khoản, mật khẩu trong **Cài đặt** (để trống tài khoản = không
cần đăng nhập; nếu đặt thì dùng HTTP Basic auth).

Các endpoint:

| Phương thức | Đường dẫn | Ý nghĩa |
|---|---|---|
| `GET` | `/` | Trang trạng thái HTML + ví dụ |
| `GET` | `/scenarios` | JSON danh sách kịch bản (id, tên, số bước) |
| `GET`/`POST` | `/run?name=<tên>&<tham_số>=<giá_trị>...` | Chạy kịch bản |
| `GET`/`POST` | `/run/<tên>` | Dạng rút gọn |

Mã trả về: `200` chạy được, `400` thiếu `name`, `401` sai đăng nhập,
`404` không có kịch bản, `409` chưa bật dịch vụ Trợ năng.

Ví dụ:

```bash
# Không đặt mật khẩu
curl "http://192.168.1.5:8900/run?name=DiemDanh&name_hv=An"

# Có đặt tài khoản/mật khẩu
curl -u admin:123456 "http://192.168.1.5:8900/run/DiemDanh"

curl "http://192.168.1.5:8900/scenarios"
```

Mọi tham số (ngoài `name`/`id`) sẽ được thay vào các chỗ `{{ten}}` trong các bước.

---

## Thư viện dùng

- `com.google.android.gms:play-services-mlkit-text-recognition:19.0.1` — OCR cho
  bước "Chạm theo chữ" (mô hình tải qua Play services, không nhúng trong APK).
- `org.json` — chỉ trên classpath test, để kiểm thử chuyển đổi JSON ↔ model.
- Phần còn lại dùng API Android/AndroidX gốc.

## Kiểm thử

Logic thuần (JSON của kịch bản/bước, thay thế tham số) có unit test JVM:

```bat
gradlew.bat :app:testDebugUnitTest
```

---

## Tự động bấm chữ (chạy nền)

Khác với bước `TAP_TEXT` trong kịch bản: chức năng này **chạy liên tục ở nền**, đọc chữ
thẳng từ cây giao diện (`AccessibilityNodeInfo`) thay vì chụp màn hình + OCR — nên
**không cần quyền ghi màn hình**, nhẹ hơn, và chạy được trên **tivi không có cảm ứng**.
Mục đích chính: tự bấm **Skip / Bỏ qua quảng cáo** khi xem YouTube.

Bật bằng switch ở màn hình chính, cấu hình trong **Danh sách chữ & tuỳ chọn**.

- **Danh sách chữ**: thêm / sửa / xoá, bật tắt từng dòng. Mỗi dòng chọn kiểu khớp:
  - **Đúng y hệt** — chữ trên màn hình phải giống hệt (mặc định).
  - **Chứa** — chỉ cần có đoạn chữ này bên trong.
- **Phân biệt chữ hoa / chữ thường**: bật sẵn, nên `Skip` không khớp `skip` hay `SKIP`.
- Mặc định có sẵn: `Skip Ads`, `Skip Ad`, `Skip ads`, `Skip`, `Bỏ qua quảng cáo`, `Bỏ qua`.
- Khớp cả **text** lẫn **content description** của phần tử trên màn hình.
- **Cách bấm**: ưu tiên `ACTION_CLICK` lên đúng nút (leo lên tối đa 6 cấp cha để tìm nút
  bấm được) — cách này không cần cảm ứng nên chạy được trên tivi. Tắt
  "Chỉ bấm vào nút bấm được" thì khi chữ không phải nút, app sẽ chạm thẳng vào toạ độ chữ
  (cần màn cảm ứng).
- **Tốc độ**:
  - *Quét lại mỗi (ms)*: `0` = chỉ bấm khi hệ thống báo màn hình đổi (ít tốn pin nhất);
    `150–10000` = quét thêm theo chu kỳ, bắt được cả nút hiện ra im lặng. Mặc định `600`.
  - *Nghỉ giữa 2 lần bấm*: cùng một chữ chỉ được bấm lại sau khoảng này. Mặc định `1500 ms`.
- **Chỉ chạy trong ứng dụng**: chọn từ danh sách app có icon (gồm cả app TV); để trống =
  mọi ứng dụng. Không bao giờ tự bấm vào chính AutoTap.
- **Nhật ký**: 30 lần bấm gần nhất (giờ, chữ, app, cách bấm). Có tuỳ chọn hiện thông báo
  mỗi lần bấm để dò khi mới cấu hình.
- **Không đấm nhau với kịch bản**: khi một kịch bản đang chạy, phần tự bấm chữ tạm nghỉ.

**Quyền:** dùng chung **dịch vụ Trợ năng AutoTap** với phần chạy kịch bản — bật một lần là đủ.
Màn hình của chức năng có dòng trạng thái và nút **Mở Cài đặt › Trợ năng** để bật ngay.

---

## Android TV

- App hiện trong launcher của tivi (`LEANBACK_LAUNCHER` + banner riêng), không bắt buộc
  màn cảm ứng.
- Mọi hàng bấm được đều nhận focus bằng phím điều hướng của remote, có viền sáng khi được chọn.
- Ghi thao tác / Chọn điểm / OCR vẫn là các tính năng thiên về cảm ứng; trên tivi nên dùng
  **tự động bấm chữ**.

---

## Quyền

`BIND_ACCESSIBILITY_SERVICE`, `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE` (+
`_MEDIA_PROJECTION`, `_DATA_SYNC`), `INTERNET`, `ACCESS_WIFI_STATE`, `WAKE_LOCK`,
`POST_NOTIFICATIONS`.

## Lưu ý về độ tin cậy

- Mọi hành vi trên thiết bị (chạm thật, OCR, ghi thao tác, API) **chưa được kiểm
  thử trên máy thật** trong quá trình phát triển — mới chỉ kiểm tra biên dịch và
  unit test. Các ngoại lệ quanh gesture, chụp màn hình, OCR và socket đều được bắt
  để ứng dụng không bị crash cứng.

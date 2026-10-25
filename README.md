# Dsoft Android Projects

Các app Android nằm trong `WebRTC/Mobile/<App>`. Mỗi app build bằng `build_apk.bat`
(APK copy sang `WebRTC/__Result/`). Build desktop (ProxySpy) ở `WebRTC/Desktop/`.

---

## 🔑 Keystore ký app (quan trọng cho đăng nhập Google/OAuth)

App Android phải được **ký**. SHA‑1 của keystore dùng để đăng ký **OAuth client** (đăng nhập Google, Drive…).
Nếu SHA‑1 đổi, đăng nhập Google sẽ hỏng → phải đăng ký lại.

### Debug keystore (dùng khi `build_apk.bat`)
- Android tự tạo ở `C:\Users\<user>\.android\debug.keystore` (mật khẩu `android`, alias `androiddebugkey`).
- **Mỗi lần build, nó tự được sao lưu về `_keystore/debug.keystore`** (qua `WebRTC/Mobile/_copy_result.bat`).
- **Giữ lại `_keystore/debug.keystore`** (nên commit — không bí mật) để **SHA‑1 không đổi khi cài lại Windows / đổi máy**.
- Khôi phục trên máy mới: copy `_keystore/debug.keystore` → `C:\Users\<user>\.android\debug.keystore`.

### Release keystore (khi phát hành thật)
- Chạy **`make_release_keystore.bat`** (nhập tên/alias/mật khẩu/số năm) → tạo `_keystore/release.jks`.
- ⚠️ **SAO LƯU file này + nhớ mật khẩu**. Mất là **không cập nhật app được nữa**. (File này **KHÔNG commit** — xem `.gitignore`.)
- Lệnh tối thiểu tương đương (nếu thích gõ tay):
  ```
  keytool -genkeypair -v -keystore _keystore\release.jks -alias dsoft -keyalg RSA -keysize 2048 -validity 10950
  ```

### Lấy SHA‑1 (để đăng ký OAuth)
- Chạy **`show_sha1.bat`** (in SHA‑1/SHA‑256 của debug + release).
- Hoặc gõ tay:
  ```
  keytool -list -v -keystore _keystore\debug.keystore -alias androiddebugkey -storepass android
  ```

---

## 🔐 Đăng nhập Google / Drive (mô hình: mỗi người 1 Drive riêng)

Mỗi người đăng nhập tài khoản Google của họ → app lưu vào Drive **của chính họ**.

Thiết lập **một lần** (phía dev) trên [Google Cloud Console](https://console.cloud.google.com):
1. Tạo project → bật **Google Drive API**.
2. Tạo **OAuth client ID** loại **Android**: nhập **package** (vd `com.dsoft.diary`) + **SHA‑1** (từ `show_sha1.bat`).
   - Đăng ký cả SHA‑1 **debug** và **release** nếu dùng cả hai.
3. **OAuth consent screen**: scope đề xuất **`drive.file`** (chỉ file app tạo — nhẹ, thường không phải thẩm định).

**Trạng thái OAuth consent screen:**
- **Testing**: chỉ email khai là *test user* (~100) đăng nhập được; refresh token hết hạn ~7 ngày (phải đăng nhập lại định kỳ). Đủ cho dùng cá nhân/nhóm nhỏ.
- **In production / Published**: ai cũng đăng nhập, token bền. **KHÔNG liên quan Play Store** — chỉ là trạng thái của màn hình đồng ý OAuth; app vẫn cài sideload bình thường. Với scope `drive.file` thường không phải thẩm định bảo mật nặng.

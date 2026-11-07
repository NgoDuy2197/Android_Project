# Build & Release — quy ước chung

Áp dụng cho mọi app trong `WebRTC/Mobile/`.

## 1. Build

```bat
build_all.bat            :: build tất cả project có build_apk.bat
build_all.bat Toolbox    :: chỉ build 1 project
build_all.bat Toolbox install   :: build rồi forward "install" cho script con (adb install)
build_all.bat list       :: liệt kê project build được
```

Mỗi project vẫn giữ `build_apk.bat` riêng (đã chuẩn hoá tên — trước đây JGamer/volte_guard dùng `build.bat`).
APK ra nằm ở thư mục project + bản copy ở `WebRTC/__Result/`.

> Ghi chú: `SentryRoom` là server Python (không phải app Android) → không có `build_apk.bat`, sẽ nằm trong mục SKIPPED. Bình thường.

## 2. Sơ đồ phiên bản (thống nhất)

- **versionName** = SemVer `MAJOR.MINOR.PATCH`, khởi điểm `1.0.0`.
- **versionCode** = `MAJOR*10000 + MINOR*100 + PATCH` → đơn điệu tăng, đọc được:
  - `1.0.0` → `10000`
  - `1.2.3` → `10203`
  - `2.0.0` → `20000`

**Native Kotlin** (`app/build.gradle.kts`):
```kotlin
versionCode = 10000
versionName = "1.0.0"
```

**Flutter** (`pubspec.yaml`):
```yaml
version: 1.0.0+10000   # <versionName>+<versionCode>
```

## 3. Ký release (release signing)

Debug build dùng `debug.keystore` tự động (đã backup ở `_keystore/`). Để lên CH Play cần **release keystore**:

1. Tạo 1 lần:
   ```bat
   keytool -genkey -v -keystore dsoft-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias dsoft
   ```
   Giữ file này an toàn, **không commit** (thêm vào `.gitignore`).
2. Mỗi project tạo `keystore.properties` (gitignored):
   ```
   storeFile=../../dsoft-release.jks
   storePassword=...
   keyAlias=dsoft
   keyPassword=...
   ```
3. Trong `app/build.gradle.kts` đọc file đó cho `signingConfigs.release` và gán vào `buildTypes.release`.

> Chưa áp dụng signingConfig release cho toàn bộ 24 app — nói 1 câu nếu muốn tôi thêm hàng loạt.

## 4. Changelog & tag

- Mỗi app giữ `CHANGELOG.md` (mục theo version, mô tả ngắn thay đổi).
- Khi phát hành:
  ```bat
  git add -A && git commit -m "release(Toolbox): v1.1.0"
  git tag Toolbox/v1.1.0
  git push --tags
  ```
- Quy ước tag: `<Project>/vMAJOR.MINOR.PATCH` (tách theo app vì mono-repo nhiều app).

# ODiary

Ứng dụng nhật ký cá nhân (native Kotlin, giao diện Views kiểu iOS).

## Tính năng
- Bảng lịch tháng (dashboard) → xem nhanh ngày có ghi chép.
- Trang xem ngày + trình soạn sự kiện, trình xem ảnh.
- Đọc to nội dung bằng TTS.
- Đồng bộ & sao lưu Google Drive (Accounts / Drive / AutoSync / hàng đợi Outbox để retry khi mất mạng).
- Chủ đề & hiệu ứng hiển thị (Fx).
- Xuất PDF A4 từng ngày (hợp nhất từ DailyDiary).

## Stack & quyền
- Native Kotlin, package `com.dsoft.odiary`.
- Quyền: `INTERNET`, `ACCESS_NETWORK_STATE` (chỉ để đồng bộ Drive).

## Build
```bat
build_apk.bat            :: hoặc từ thư mục Mobile: build_all.bat Diary
```
APK copy sang `WebRTC/__Result/ODiary.apk`.

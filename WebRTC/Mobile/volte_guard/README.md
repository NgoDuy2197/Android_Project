# VoLTE Guard

Giữ HD Call (VoLTE/IMS) luôn sống khi mạng đã cắt 2G. Theo dõi 1 SIM (SIM 1 mặc định), phát hiện mất IMS và tự khắc phục theo thang leo thang.

## Build / cài
```bat
build.bat            :: build debug -> VoLTEGuard.apk ở thư mục này
build.bat install    :: build + adb install + grant quyền + mở app
build.bat grant      :: chỉ grant WRITE_SECURE_SETTINGS / READ_PHONE_STATE qua adb
build.bat log        :: adb logcat -s VoLTEGuard
```

## Kiểm tra (required = phải PASS hết)
| Key | Kiểm tra | Nguồn |
|---|---|---|
| carrier_cfg | `carrier_volte_available_bool` (override Pixel IMS còn không) | CarrierConfigManager (public) |
| volte_switch | Công tắc VoLTE / 4G Calling | ITelephony qua Shizuku |
| ims_reg | `isImsRegistered(subId)` | ITelephony qua Shizuku → hidden TM |
| volte_avail | MMTEL voice khả dụng (icon HD) | ITelephony qua Shizuku → hidden TM |
| heuristic | Voice RAT = LTE/NR/IWLAN (chỉ khi không có Shizuku) | TelephonyManager |

Điều kiện chặn khắc phục: không SIM, máy bay đang bật, radio tắt, mất sóng LTE/NR, đang gọi.
Trigger: chu kỳ (cấu hình) + TelephonyCallback ServiceState/DisplayInfo + exact alarm (chống Doze).
Chống báo nhầm: cần N lần lỗi liên tiếp (mặc định 2, cách 10s), cooldown giữa 2 lần khắc phục.

## Khắc phục (dừng ngay khi HEALTHY)
1. Áp lại carrier config VoLTE (`ICarrierConfigLoader.overrideConfig`) — chỉ khi carrier_cfg FAIL
2. Bật công tắc VoLTE — chỉ khi volte_switch FAIL
3. Reset IMS (`ITelephony.resetIms` → `cmd phone ims disable/enable`)
4. `svc data disable/enable`
5. Airplane: `cmd connectivity airplane-mode` (Shizuku) → fallback `WRITE_SECURE_SETTINGS`
6. Reboot modem (`ITelephony.rebootModem`) — tắt mặc định

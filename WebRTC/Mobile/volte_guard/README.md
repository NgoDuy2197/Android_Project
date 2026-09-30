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

## Khắc phục (nhanh trước, dừng ngay khi HEALTHY, re-check mỗi 2s)
1. **Bật lại công tắc VoLTE (restore)** — ép công tắc = ON (`setAdvancedCallingSettingEnabled(true)`, fallback `service call phone <code> i32 sub i32 1`). Nhanh & ổn định nhất, chạy đầu tiên (giống `test_fault.bat restore`)
2. Nháy công tắc VoLTE (off→on) — ~3s
3. Áp lại carrier config VoLTE (`ICarrierConfigLoader.overrideConfig`) — chỉ khi carrier_cfg FAIL
4. Reset IMS (`cmd phone ims disable/enable` → `ITelephony.resetIms`) — ~5s
5. `svc data disable/enable` — ~5s
6. Reboot modem (`ITelephony.rebootModem` → `cmd phone restart-modem`) — tắt mặc định

Đã bỏ bước Airplane (không hiệu quả trong thực tế).

Mặc định: check mỗi 20s, lỗi phải lặp 2 lần (cách 5s), cooldown 120s, chờ bước chậm 25s.

## Test thủ công từng cách (trong app)
Ngoài công tắc bật/tắt, màn hình chính có thẻ **Test thủ công** với nút bấm riêng cho từng cách (restore / nháy / carrier / reset IMS / data / modem) để tự thử xem cách nào lấy lại HD. Kết quả hiện ở Nhật ký. Cần Shizuku.

`test_fault.bat` — tắt công tắc VoLTE / làm mất carrier config rồi theo dõi IMS tới khi app tự khôi phục.

## Tự bật lại sau khi khởi động máy
`BootReceiver` bắt `BOOT_COMPLETED` / `QUICKBOOT_POWERON` → start GuardService + đặt 1 alarm dự phòng start lại. **Lưu ý:** trên ColorOS/MIUI/… cần bật quyền **Tự khởi động (Auto-start)** cho app trong cài đặt hệ thống, nếu không hệ thống chặn broadcast lúc boot. Shizuku (qua ADB) chết khi reboot → cần bật lại Shizuku thì watchdog + các bước cần Shizuku mới hoạt động lại.

## Watchdog (shell) — sống sót khi app bị vuốt tắt / ColorOS đóng băng
Khi có Shizuku, app chạy `/data/local/tmp/volteguard_wd.sh` bằng uid shell (tách khỏi app, cha là init):
- mỗi chu kỳ: `am start-foreground-service` GuardService → hồi sinh app nếu bị kill/force-stop, rã đông nếu bị Hans freeze
- IMS mất ≥4 chu kỳ liên tiếp (app không tự fix được) → tự nháy công tắc VoLTE qua `service call phone`, backoff 30s→600s
- GuardService export nhưng yêu cầu `android.permission.DUMP` (chỉ shell/system gọi được)
- Chết khi Shizuku chết (reboot) → start lại Shizuku là app tự bật lại watchdog
Log: `adb logcat -s VoLTEGuardWD:*`

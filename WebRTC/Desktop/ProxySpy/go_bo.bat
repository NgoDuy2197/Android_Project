@echo off
REM ============================================================================
REM  go_bo.bat — tra may ve binh thuong:
REM    1) go proxy toan cuc + adb reverse
REM    2) go bind-mount CA (tra lai kho CA he thong goc, KHONG can reboot)
REM    3) restart cac app Google (CH Play / GMS / Photos)
REM ============================================================================
setlocal
set "ADB=adb"
where adb >nul 2>nul || set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"

echo [1/3] Go proxy toan cuc + adb reverse...
"%ADB%" shell settings put global http_proxy :0
"%ADB%" reverse --remove-all 2>nul

echo [2/3] Go bind-mount CA (tra lai ~140 CA he thong goc)...
"%ADB%" shell su -c "sh -c 'for t in /apex/com.android.conscrypt/cacerts /system/etc/security/cacerts; do umount -l \"$t\" 2>/dev/null; umount -l \"$t\" 2>/dev/null; done; for pid in $(pidof zygote64 zygote webview_zygote app_zygote); do nsenter --mount=/proc/$pid/ns/mnt -- umount -l /apex/com.android.conscrypt/cacerts 2>/dev/null; nsenter --mount=/proc/$pid/ns/mnt -- umount -l /system/etc/security/cacerts 2>/dev/null; done'"

echo     So luong CA he thong hien tai (phai ~140, KHONG phai 1):
"%ADB%" shell su -c "ls /apex/com.android.conscrypt/cacerts | wc -l"

echo [3/3] Khoi dong lai cac app Google...
for %%P in (com.android.vending com.google.android.gms com.google.android.gsf com.google.android.apps.photos) do "%ADB%" shell am force-stop %%P

echo.
echo [OK] Xong. Mo lai CH Play.
echo     Neu dong "So luong CA" van la 1: chay lai file nay, hoac "adb reboot".
pause

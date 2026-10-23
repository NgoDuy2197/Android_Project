@echo off
REM ============================================================================
REM  go_bo.bat — trả máy về bình thường: gỡ proxy + adb reverse.
REM  CA hệ thống đã cài bằng bind-mount sẽ TỰ MẤT khi REBOOT máy (sạch hoàn toàn).
REM ============================================================================
setlocal
set "ADB=adb"
where adb >nul 2>nul || set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"

echo Go proxy toan cuc...
"%ADB%" shell settings put global http_proxy :0
echo Go adb reverse...
"%ADB%" reverse --remove-all 2>nul
echo.
echo Xong. CA he thong van con (do bind-mount) cho den khi ban REBOOT may.
echo Muon go ngay: khoi dong lai may.
pause

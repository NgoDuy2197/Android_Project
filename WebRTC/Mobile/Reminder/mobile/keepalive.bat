@echo off
chcp 65001 >nul
cd /d "%~dp0"
REM Starts the keep-alive watchdog for Reminder over USB (no Shizuku needed).
REM It runs on the phone until the phone reboots; run this again after a reboot.
REM   keepalive.bat        start
REM   keepalive.bat stop   stop
set "PKG=com.dsoft.remind_me"
set "RCV=%PKG%/.KeepAliveReceiver"
set "ADB=adb"
where adb >nul 2>nul || set "ADB=%LOCALAPPDATA%\Android\sdk\platform-tools\adb.exe"
if /i "%~1"=="stop" (
    "%ADB%" shell "p=$(cat /data/local/tmp/keepalive_%PKG%.pid 2>/dev/null); rm -f /data/local/tmp/keepalive_%PKG%.pid; [ -n \"$p\" ] && kill $p; echo stopped"
    exit /b 0
)
"%ADB%" push android\app\src\main\assets\keepalive.sh /data/local/tmp/keepalive.sh >nul || goto :fail
"%ADB%" shell "chmod 755 /data/local/tmp/keepalive.sh; setsid sh /data/local/tmp/keepalive.sh %PKG% %RCV% 30 </dev/null >/dev/null 2>&1 &" || goto :fail
echo Watchdog dang chay cho %PKG% (tat khi khoi dong lai may).
exit /b 0
:fail
echo [LOI] Khong ket noi duoc dien thoai qua adb.
exit /b 1
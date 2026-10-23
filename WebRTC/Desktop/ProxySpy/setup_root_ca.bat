@echo off
REM ============================================================================
REM  setup_root_ca.bat — BẤM PHÁT ĂN NGAY cho máy ĐÃ ROOT + USB debug.
REM  Làm hết: kiểm tra root -> tạo cert theo hash -> đặt proxy/USB -> cài CA hệ thống.
REM  Sau đó mở lại app là bắt được HTTPS của app targetSdk cao (vd FIS com.tdc.mfu).
REM
REM  Mỗi bước làm gì (siêu gọn):
REM   [1] Xác minh adb thấy máy + Magisk đã cấp root cho shell (su -> uid=0).
REM   [2] Lấy CA của ProxySpy, đổi tên theo subject_hash để Android nhận (make_syscert.py).
REM   [3] Định tuyến: adb reverse (đi qua USB) + đặt proxy máy = 127.0.0.1:8080.
REM   [4] Đẩy cert + script, bind-mount CA vào kho hệ thống (apex) + namespace zygote.
REM   [5] Force-stop app để nó fork lại và tin CA mới.
REM  (Bind-mount mất sau reboot -> chạy lại file này.)
REM ============================================================================
setlocal
cd /d "%~dp0"

set "APP=%~1"
if "%APP%"=="" set "APP=com.tdc.mfu"

set "ADB=adb"
where adb >nul 2>nul || set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"

echo [1/5] Kiem tra thiet bi + root...
"%ADB%" get-state 1>nul 2>nul || (echo [!] Khong thay thiet bi. Cam may + bat USB debug. & pause & exit /b 1)
set "ROOT="
for /f "delims=" %%r in ('"%ADB%" shell su -c id 2^>^&1') do set "ROOT=%%r"
echo     %ROOT%
echo %ROOT% | find "uid=0" >nul || (echo [!] Shell chua co root. Mo app Magisk -^> cap quyen cho Shell roi chay lai. & pause & exit /b 1)

echo [2/5] Tao cert tu CA cua ProxySpy...
if not exist "%~dp0ca\mitmproxy-ca-cert.pem" (echo [!] Chua co CA. Chay run.bat/ProxySpy mot lan truoc da. & pause & exit /b 1)
if not exist "%~dp0.venv\Scripts\python.exe" (echo [!] Chua co .venv. Chay run.bat truoc. & pause & exit /b 1)
set "HASH="
"%~dp0.venv\Scripts\python.exe" "%~dp0make_syscert.py" "%~dp0ca\mitmproxy-ca-cert.pem" "%~dp0." 1>"%TEMP%\psp_hash.txt" 2>"%TEMP%\psp_err.txt"
if exist "%TEMP%\psp_hash.txt" set /p HASH=<"%TEMP%\psp_hash.txt"
del "%TEMP%\psp_hash.txt" 2>nul
if "%HASH%"=="" (echo [!] Khong tao duoc cert. Chi tiet loi python: & type "%TEMP%\psp_err.txt" & del "%TEMP%\psp_err.txt" 2>nul & pause & exit /b 1)
del "%TEMP%\psp_err.txt" 2>nul
echo     cert = %HASH%.0

echo [3/5] Dinh tuyen proxy qua USB...
"%ADB%" reverse tcp:8080 tcp:8080 >nul
"%ADB%" shell su -c "settings put global http_proxy 127.0.0.1:8080"

echo [4/5] Day + cai CA he thong...
"%ADB%" push "%~dp0root_system_ca.sh" /data/local/tmp/ >nul
"%ADB%" push "%~dp0%HASH%.0" /data/local/tmp/ >nul
"%ADB%" shell su -c "sh /data/local/tmp/root_system_ca.sh"

echo [5/5] Mo lai app %APP% de no tin CA moi...
"%ADB%" shell am force-stop %APP%
echo.
echo === XONG. Mo app %APP% tren may, request se hien o dashboard http://127.0.0.1:8081
echo === Go sach khi xong:  go_bo.bat  (hoac reboot may)
pause

@echo off
REM ============================================================================
REM  setup_client_root.bat — kịch bản 2 máy:
REM    - Máy A: chạy APP ProxySpy (Android), đã "Bật proxy". Lấy IP của A trong app.
REM    - Máy B: ĐÃ ROOT + cắm USB vào PC này. Là máy cần soi (máy đích).
REM    - PC + A + B cùng một Wi-Fi.
REM  Bat này (chạy trên PC, tác động lên máy B):
REM    lấy CA từ app A (qua proxy) -> đặt proxy B về A -> cài CA hệ thống trên B -> mở lại app.
REM
REM  Dùng:  setup_client_root.bat <IP_cua_A>[:port]  [package]
REM  VD:    setup_client_root.bat 192.168.1.50:8080 com.example.app
REM ============================================================================
setlocal
cd /d "%~dp0"

set "A=%~1"
if "%A%"=="" ( echo Dung: setup_client_root.bat ^<IP_may_chay_app^>[:port] [package] & pause & exit /b 1 )
echo %A% | find ":" >nul || set "A=%A%:8080"
set "APP=%~2"
if "%APP%"=="" set "APP="

set "ADB=adb"
where adb >nul 2>nul || set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"

echo [1/5] Kiem tra root tren may B...
set "ROOT="
for /f "delims=" %%r in ('"%ADB%" shell su -c id 2^>^&1') do set "ROOT=%%r"
echo     %ROOT%
echo %ROOT% | find "uid=0" >nul || ( echo [!] May B chua co root shell. Mo Magisk -^> cap quyen Shell roi chay lai. & pause & exit /b 1 )

echo [2/5] Lay CA tu app ProxySpy tren %A% ...
curl.exe -s -x "http://%A%" "http://proxyspy.ca/ca.crt" -o "%~dp0client-ca.crt"
if not exist "%~dp0client-ca.crt" ( echo [!] Khong tai duoc CA. PC co cung Wi-Fi voi A? App da "Bat proxy" chua? Dung IP:port chua? & pause & exit /b 1 )
for %%S in ("%~dp0client-ca.crt") do if %%~zS LSS 100 ( echo [!] File CA rong/loi. Kiem tra proxy A. & del "%~dp0client-ca.crt" & pause & exit /b 1 )

echo [3/5] Tao cert theo hash...
if not exist "%~dp0.venv\Scripts\python.exe" ( echo [!] Chua co .venv. Chay run.bat (ban PC) mot lan truoc. & pause & exit /b 1 )
"%~dp0.venv\Scripts\python.exe" "%~dp0make_syscert.py" "%~dp0client-ca.crt" "%~dp0." 1>"%TEMP%\psp_h.txt" 2>"%TEMP%\psp_e.txt"
set "HASH="
if exist "%TEMP%\psp_h.txt" set /p HASH=<"%TEMP%\psp_h.txt"
del "%TEMP%\psp_h.txt" 2>nul
if "%HASH%"=="" ( echo [!] Khong tao duoc cert. Chi tiet: & type "%TEMP%\psp_e.txt" & del "%TEMP%\psp_e.txt" 2>nul & pause & exit /b 1 )
del "%TEMP%\psp_e.txt" 2>nul
echo     cert = %HASH%.0

echo [4/5] Dat proxy B -> %A% + cai CA he thong tren B...
echo     LUU Y: dang nhap xong (vd 2FA) TRUOC khi chay bat nay, roi giu nguyen app.
"%ADB%" shell su -c "settings put global http_proxy %A%"
"%ADB%" push "%~dp0root_system_ca.sh" /data/local/tmp/ >nul
"%ADB%" push "%~dp0%HASH%.0" /data/local/tmp/ >nul

echo [5/5] Cai CA + tiem thang vao %APP% dang chay (GIU phien dang nhap, khong dong app)...
"%ADB%" shell su -c "sh /data/local/tmp/root_system_ca.sh %APP%"
echo.
echo === XONG. KHONG can mo lai app — chi thao tac moi trong %APP% (vd mo lai mini-app/trang).
echo === Request se hien tren dien thoai dang chay ProxySpy (may A).
echo === Neu app van khong tin CA: them tick/dong app roi chay lai; hoac dung Magisk module (ban mobile).
echo === Tra may B ve binh thuong khi xong:  go_bo.bat
pause

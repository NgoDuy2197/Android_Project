@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul
cd /d "%~dp0"

echo ============================================
echo   Camera WebRTC - Flutter Client Runner
echo ============================================
echo.

REM --- 1. Kiem tra Flutter ---
where flutter >nul 2>nul
if errorlevel 1 (
    echo [LOI] Khong tim thay Flutter trong PATH.
    echo Vui long cai dat Flutter SDK: https://docs.flutter.dev/get-started/install
    pause
    exit /b 1
)
echo [1/4] Kiem tra Flutter... OK
echo.

REM --- 2. Cai dat thu vien ---
echo [2/4] Cai dat thu vien (flutter pub get)...
call flutter pub get
if errorlevel 1 (
    echo [LOI] flutter pub get that bai.
    pause
    exit /b 1
)
echo.

REM --- 3. Tu dong tim thiet bi Android dang ket noi ---
echo [3/4] Dang tim thiet bi Android...
set "DEVICE_ID="
set "DEV_FILE=%TEMP%\webrtc_android_device.txt"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$d = (flutter devices --machine | ConvertFrom-Json) | Where-Object { $_.targetPlatform -like 'android*' } | Select-Object -First 1; if ($d) { $d.id }" > "%DEV_FILE%"
set /p DEVICE_ID=<"%DEV_FILE%"
del "%DEV_FILE%" >nul 2>nul

if "%DEVICE_ID%"=="" (
    echo [LOI] Khong tim thay thiet bi Android nao.
    echo   - Cam USB dien thoai, bat "Go / USB debugging"
    echo   - Cho phep "Allow USB debugging" khi dien thoai hoi
    echo   - Kiem tra bang lenh: flutter devices
    pause
    exit /b 1
)
echo    Da tim thay thiet bi: %DEVICE_ID%
echo.

REM --- 4. Chay app tren thiet bi do ---
echo [4/4] Chay app tren %DEVICE_ID%...
echo.
call flutter run -d %DEVICE_ID%

echo.
echo App da dung.
pause

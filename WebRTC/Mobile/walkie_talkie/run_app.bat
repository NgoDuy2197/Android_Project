@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul
cd /d "%~dp0"

echo ============================================
echo   Walkie Talkie - Chay tren thiet bi
echo ============================================
echo.

where flutter >nul 2>nul
if errorlevel 1 (
    echo [LOI] Khong tim thay Flutter trong PATH.
    pause
    exit /b 1
)
echo [1/3] Kiem tra Flutter... OK
echo.

echo [2/3] Cai dat thu vien (flutter pub get)...
call flutter pub get
if errorlevel 1 (
    echo [LOI] flutter pub get that bai.
    pause
    exit /b 1
)
echo.

echo [3/3] Dang tim thiet bi Android...
set "DEVICE_ID="
set "DEV_FILE=%TEMP%\walkie_android_device.txt"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$d = (flutter devices --machine | ConvertFrom-Json) | Where-Object { $_.targetPlatform -like 'android*' } | Select-Object -First 1; if ($d) { $d.id }" > "%DEV_FILE%"
set /p DEVICE_ID=<"%DEV_FILE%"
del "%DEV_FILE%" >nul 2>nul

if "%DEVICE_ID%"=="" (
    echo [LOI] Khong tim thay thiet bi Android. Cam USB + bat USB debug.
    pause
    exit /b 1
)
echo    Thiet bi: %DEVICE_ID%
echo.
call flutter run -d %DEVICE_ID%

echo.
echo App da dung.
pause

@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul
cd /d "%~dp0"

echo ============================================
echo   Camera WebRTC - Build APK cai dat
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

REM --- 3. Sinh icon app tu icon.png ---
echo [3/4] Tao icon app tu icon.png...
if not exist "icon.png" (
    echo [CANH BAO] Khong tim thay icon.png, bo qua buoc tao icon.
) else (
    call dart run flutter_launcher_icons
    if errorlevel 1 (
        echo [LOI] Tao icon that bai.
        pause
        exit /b 1
    )
)
echo.

REM --- 4. Build APK release ---
echo [4/4] Dang build APK (release)... Lan dau co the mat vai phut.
echo.
call flutter build apk --release
if errorlevel 1 (
    echo.
    echo [LOI] Build APK that bai. Xem log o tren.
    pause
    exit /b 1
)

set "APK=build\app\outputs\flutter-apk\app-release.apk"
REM --- Copy APK ra thu muc goc + doi ten theo app ---
set "OUT=%~dp0web_rtc_client.apk"
if exist "%APK%" copy /Y "%APK%" "%OUT%" >nul
echo.
echo ============================================
echo   BUILD THANH CONG
echo ============================================
echo   File APK: %OUT%
echo.
echo   Cach cai: copy file APK sang dien thoai roi bam cai dat
echo   (bat "Cai dat tu nguon khong xac dinh" neu duoc hoi).
echo ============================================

REM Mo thu muc chua APK cho tien
if exist "%OUT%" explorer /select,"%OUT%"

echo.
pause

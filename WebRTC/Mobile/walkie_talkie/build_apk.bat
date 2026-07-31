@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul
cd /d "%~dp0"

echo ============================================
echo   Walkie Talkie - Build APK cai dat
echo ============================================
echo.

where flutter >nul 2>nul
if errorlevel 1 (
    echo [LOI] Khong tim thay Flutter trong PATH.
    echo Cai Flutter SDK: https://docs.flutter.dev/get-started/install
    pause
    exit /b 1
)
echo [1/4] Kiem tra Flutter... OK
echo.

echo [2/4] Cai dat thu vien (flutter pub get)...
call flutter pub get
if errorlevel 1 (
    echo [LOI] flutter pub get that bai.
    pause
    exit /b 1
)
echo.

echo [3/4] Tao icon app tu icon.png (neu co)...
if not exist "icon.png" (
    echo [BO QUA] Khong co icon.png, dung icon mac dinh.
) else (
    call dart run flutter_launcher_icons
    if errorlevel 1 (
        echo [CANH BAO] Tao icon that bai, tiep tuc build.
    )
)
echo.

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
set "OUT=%~dp0walkie_talkie.apk"
if exist "%APK%" copy /Y "%APK%" "%OUT%" >nul
echo.
echo ============================================
echo   BUILD THANH CONG
echo ============================================
echo   File APK: %OUT%
echo.
echo   Cai APK nay len CA HAI dien thoai.
echo   Mot may chon Speaker, may kia chon Remoter.
echo ============================================
if exist "%OUT%" explorer /select,"%OUT%"
echo.
pause

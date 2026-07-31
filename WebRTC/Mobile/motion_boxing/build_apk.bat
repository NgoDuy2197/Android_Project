@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul
cd /d "%~dp0"

echo ============================================
echo   Motion Boxing - Build APK cai dat
echo ============================================
echo.

where flutter >nul 2>nul
if errorlevel 1 (
    echo [LOI] Khong tim thay Flutter trong PATH.
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

echo [3/4] Tao icon app tu icon.png...
if not exist "icon.png" (
    echo [BO QUA] Khong co icon.png.
) else (
    call dart run flutter_launcher_icons
    if errorlevel 1 echo [CANH BAO] Tao icon that bai, tiep tuc build.
)
echo.

echo [4/4] Dang build APK (release)...
call flutter build apk --release
if errorlevel 1 (
    echo [LOI] Build APK that bai.
    pause
    exit /b 1
)

set "APK=build\app\outputs\flutter-apk\app-release.apk"
REM --- Copy APK ra thu muc goc + doi ten theo app ---
set "OUT=%~dp0motion_boxing.apk"
if exist "%APK%" copy /Y "%APK%" "%OUT%" >nul
echo.
echo ============================================
echo   BUILD THANH CONG
echo   File APK: %OUT%
echo ============================================
if exist "%OUT%" explorer /select,"%OUT%"
echo.
pause

@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul
cd /d "%~dp0"
echo === Voice AI - Build APK ===
where flutter >nul 2>nul || (echo [LOI] Khong co Flutter trong PATH. & pause & exit /b 1)
call flutter pub get || (echo [LOI] pub get that bai. & pause & exit /b 1)
call flutter build apk --release || (echo [LOI] Build that bai. & pause & exit /b 1)
set "APK=build\app\outputs\flutter-apk\app-release.apk"
set "OUT=%~dp0voice_ai.apk"
if exist "%APK%" copy /Y "%APK%" "%OUT%" >nul
echo.
echo BUILD THANH CONG - APK: %OUT%
if exist "%OUT%" explorer /select,"%OUT%"
pause

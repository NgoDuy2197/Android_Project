@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"

echo ============================================
echo   WebRTC Camera - Signaling Server
echo ============================================
echo.

REM --- 1. Kiem tra Node.js ---
where node >nul 2>nul
if errorlevel 1 (
    echo [LOI] Khong tim thay Node.js.
    echo Vui long cai dat Node.js LTS: https://nodejs.org/
    echo Sau khi cai xong, chay lai file nay.
    pause
    exit /b 1
)
for /f "delims=" %%v in ('node -v') do echo [OK] Node.js %%v

REM --- 2. Cai dat thu vien (chi khi chua co node_modules) ---
if not exist "node_modules" (
    echo.
    echo [*] Dang cai dat thu vien lan dau ^(npm install^)...
    call npm install
    if errorlevel 1 (
        echo [LOI] npm install that bai.
        pause
        exit /b 1
    )
) else (
    echo [OK] Thu vien da co san.
)

REM --- 3. Start server ---
echo.
echo [*] Dang khoi dong server...
echo.
node server.js

echo.
echo Server da dung.
pause

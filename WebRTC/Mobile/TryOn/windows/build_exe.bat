@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul
cd /d "%~dp0"

REM ============================================
REM   TryOn (Windows) - Build TryOn.exe
REM   build_exe.bat        -> windows\TryOn.exe
REM   build_exe.bat clean  -> xoa venv / build cu
REM ============================================

if /i "%~1"=="clean" (
    rmdir /s /q .venv build dist 2>nul
    del /q TryOn.spec icon.ico 2>nul
    echo Da xoa .venv, build, dist.
    exit /b 0
)

echo ============================================
echo   TryOn (Windows) - Build EXE
echo ============================================
echo.

REM --- 1. Python venv (MediaPipe can Python 3.9 - 3.13, 64-bit) ---
if not exist ".venv\Scripts\python.exe" (
    echo [1/5] Tao moi truong Python .venv...
    py -3.13 -m venv .venv 2>nul || py -3 -m venv .venv 2>nul || python -m venv .venv || goto :fail
) else (
    echo [1/5] Da co .venv
)
set "PY=.venv\Scripts\python.exe"

echo [2/5] Cai thu vien (lan dau mat vai phut)...
"%PY%" -m pip install --disable-pip-version-check -q --upgrade pip
"%PY%" -m pip install --disable-pip-version-check -q -r requirements.txt pyinstaller || goto :fail

REM --- 2. Model nhan dien co the (Google MediaPipe, ~9 MB) ---
if not exist "models\pose_landmarker_full.task" (
    echo [3/5] Tai model nhan dien co the...
    if not exist models mkdir models
    curl -sSL -o "models\pose_landmarker_full.task" "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_full/float16/latest/pose_landmarker_full.task" || goto :fail
) else (
    echo [3/5] Da co model
)

REM --- 3. Icon .ico tu icon.png ---
echo [4/5] Tao icon...
"%PY%" -c "from PySide6.QtGui import QImage; QImage('icon.png').scaled(256,256).save('icon.ico')" || goto :fail

REM --- 4. PyInstaller (anh mau dung chung voi ban mobile) ---
echo [5/5] Dang build TryOn.exe...
"%PY%" -m PyInstaller --noconfirm --clean --onefile --noconsole --name TryOn --icon icon.ico ^
    --add-data "models;models" ^
    --add-data "..\mobile\assets\samples;samples" ^
    --add-data "icon.png;." ^
    --collect-all mediapipe ^
    tryon.py || goto :fail

copy /Y "dist\TryOn.exe" "TryOn.exe" >nul
call "%~dp0..\..\_copy_result.bat" "%~dp0TryOn.exe"
echo.
echo ============================================
echo   BUILD THANH CONG
echo   File EXE: %~dp0TryOn.exe
echo ============================================
if exist "TryOn.exe" explorer /select,"%~dp0TryOn.exe"
echo.
pause
exit /b 0

:fail
echo.
echo [LOI] Build that bai. Xem log o tren.
pause
exit /b 1

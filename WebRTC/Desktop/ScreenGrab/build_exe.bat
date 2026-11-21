@echo off
REM ============================================================================
REM  Package ScreenGrab desktop into dist\ScreenGrab.exe and copy to __Result.
REM  Run run.bat at least once first (to create .venv + install deps).
REM ============================================================================
setlocal
cd /d "%~dp0"

if not exist ".venv\Scripts\python.exe" (
    echo [!] Chua co .venv. Hay chay run.bat truoc.
    pause & exit /b 1
)

".venv\Scripts\python.exe" -m pip install pyinstaller -q
".venv\Scripts\python.exe" make_icon.py

".venv\Scripts\pyinstaller.exe" --noconfirm --clean --onefile --windowed --name ScreenGrab ^
  --icon icon.ico ^
  --add-data "icon.png;." ^
  --collect-all imageio_ffmpeg ^
  app.py
if errorlevel 1 ( echo [!] Build that bai. & pause & exit /b 1 )

call "..\..\Mobile\_copy_result.bat" "dist\ScreenGrab.exe"
echo [build] Xong: dist\ScreenGrab.exe

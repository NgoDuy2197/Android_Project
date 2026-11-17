@echo off
REM ============================================================================
REM  Package the RemoteLink desktop viewer into dist\RemoteLink.exe and copy to __Result.
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

".venv\Scripts\pyinstaller.exe" --noconfirm --clean --onefile --windowed --name RemoteLink ^
  --icon icon.ico ^
  --add-data "icon.png;." ^
  --collect-all av ^
  app.py
if errorlevel 1 ( echo [!] Build that bai. & pause & exit /b 1 )

call "..\..\Mobile\_copy_result.bat" "dist\RemoteLink.exe"
echo [build] Xong: dist\RemoteLink.exe

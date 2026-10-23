@echo off
REM ============================================================================
REM  Đóng gói ProxySpy thành 1 file dist\ProxySpy.exe (PyInstaller) và copy vào __Result.
REM  Cần chạy run.bat ít nhất 1 lần trước (để có .venv + các gói).
REM ============================================================================
setlocal
cd /d "%~dp0"

if not exist ".venv\Scripts\python.exe" (
    echo [!] Chua co .venv. Hay chay run.bat truoc.
    pause & exit /b 1
)

".venv\Scripts\python.exe" -m pip install pyinstaller -q

".venv\Scripts\pyinstaller.exe" --noconfirm --clean --onefile --console --name ProxySpy ^
  --add-data "dashboard.html;." ^
  --collect-all mitmproxy ^
  --collect-all mitmproxy_rs ^
  --collect-submodules segno ^
  app.py
if errorlevel 1 ( echo [!] Build that bai. & pause & exit /b 1 )

call "..\..\Mobile\_copy_result.bat" "dist\ProxySpy.exe"
echo [build] Xong: dist\ProxySpy.exe

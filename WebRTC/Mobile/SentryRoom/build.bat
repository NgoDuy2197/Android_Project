@echo off
REM Đóng gói thành 1 file .exe nhẹ (có icon), không cần cài Python để chạy.
pip install -r requirements.txt pyinstaller
pyinstaller --onefile --name SentryRoom --icon icon.ico ^
  --add-data "web;web" ^
  --hidden-import engineio.async_drivers.aiohttp ^
  server.py
echo.
echo Done. File: dist\SentryRoom.exe  (chay truc tiep, khong can Python)
pause

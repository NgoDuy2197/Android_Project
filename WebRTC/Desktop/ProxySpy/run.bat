@echo off
REM ============================================================================
REM  ProxySpy launcher — venv + mitmproxy, mở firewall cho máy cùng mạng, chạy app.
REM  Usage:  run.bat                       (proxy :8080, dashboard :8081)
REM          run.bat --port 9090 --dash-port 9091
REM ============================================================================
setlocal EnableDelayedExpansion
cd /d "%~dp0"
set "ARGS=%*"

REM --- đọc cổng từ tham số (để mở đúng port trên firewall) ---
set "FWP=8080"
set "FWD=8081"
:parse
if "%~1"=="" goto parsed
if /I "%~1"=="--port"      set "FWP=%~2"
if /I "%~1"=="--dash-port" set "FWD=%~2"
shift
goto parse
:parsed

REM --- firewall: cho phép máy cùng mạng kết nối tới proxy + dashboard ---
netsh advfirewall firewall show rule name="ProxySpy" >nul 2>&1
if errorlevel 1 (
    echo [firewall] Mo port !FWP!,!FWD! cho may cung mang ^(se hien UAC xin quyen Admin^)...
    powershell -NoProfile -Command "try { Start-Process netsh -Verb RunAs -Wait -ArgumentList 'advfirewall firewall add rule name=ProxySpy dir=in action=allow protocol=TCP localport=!FWP!,!FWD!' } catch { Write-Host '[firewall] Bo qua (neu dien thoai khong ket noi duoc, mo port !FWP! thu cong).' }"
) else (
    echo [firewall] Da co rule ProxySpy.
)

REM --- python / venv ---
where py >nul 2>nul
if %errorlevel%==0 ( set "PY=py -3" ) else ( set "PY=python" )

if not exist ".venv\Scripts\python.exe" (
    echo [setup] Tao moi truong ao .venv ...
    %PY% -m venv .venv
    if errorlevel 1 ( echo [!] Khong tao duoc venv. Can cai Python 3.9+ tu python.org. & pause & exit /b 1 )
    echo [setup] Cai goi phu thuoc ... (lan dau co the mat vai phut)
    ".venv\Scripts\python.exe" -m pip install --upgrade pip >nul
    ".venv\Scripts\python.exe" -m pip install -r requirements.txt
    if errorlevel 1 ( echo [!] Cai goi that bai. & pause & exit /b 1 )
)

".venv\Scripts\python.exe" app.py %ARGS%

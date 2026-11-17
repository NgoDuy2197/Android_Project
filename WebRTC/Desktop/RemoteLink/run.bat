@echo off
REM ============================================================================
REM  RemoteLink desktop viewer — create .venv, install deps, run the GUI.
REM ============================================================================
setlocal
cd /d "%~dp0"

where py >nul 2>nul
if %errorlevel%==0 ( set "PY=py -3" ) else ( set "PY=python" )

if not exist ".venv\Scripts\python.exe" (
    echo [setup] Tao moi truong ao .venv ...
    %PY% -m venv .venv
    if errorlevel 1 ( echo [!] Khong tao duoc venv. Can Python 3.9+ tu python.org. & pause & exit /b 1 )
    echo [setup] Cai goi phu thuoc ... (lan dau co the mat vai phut)
    ".venv\Scripts\python.exe" -m pip install --upgrade pip >nul
    ".venv\Scripts\python.exe" -m pip install -r requirements.txt
    if errorlevel 1 ( echo [!] Cai goi that bai. & pause & exit /b 1 )
)

if not exist "icon.png" ".venv\Scripts\python.exe" make_icon.py

".venv\Scripts\python.exe" app.py %*

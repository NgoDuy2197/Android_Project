@echo off
REM ============================================================================
REM  VoiceNote (mic -> text) - one-click EXE builder for Windows
REM    build_exe.bat        -> VoiceNote.exe (single file, tray app, no console)
REM    build_exe.bat clean  -> remove venv / build outputs
REM  Needs Python 3.9+ on PATH.
REM ============================================================================
setlocal
cd /d "%~dp0"

if /I "%~1"=="clean" (
    rmdir /s /q .venv build dist 2>nul
    del /q VoiceNote.spec VoiceNote.exe 2>nul
    goto :eof
)

if not exist ".venv\Scripts\python.exe" (
    echo [build] Creating venv...
    python -m venv .venv || goto :fail
)
set "PY=.venv\Scripts\python.exe"
"%PY%" -m pip install --disable-pip-version-check -q -r requirements.txt pyinstaller || goto :fail

"%PY%" -m PyInstaller --noconfirm --clean --onefile --noconsole --name VoiceNote ^
    --collect-all vosk --collect-all _sounddevice_data --hidden-import pystray._win32 ^
    voicenote.py || goto :fail

copy /Y "dist\VoiceNote.exe" "VoiceNote.exe" >nul
echo.
echo [build] SUCCESS: %~dp0VoiceNote.exe
goto :eof

:fail
echo.
echo [build] BUILD FAILED - see output above.
exit /b 1

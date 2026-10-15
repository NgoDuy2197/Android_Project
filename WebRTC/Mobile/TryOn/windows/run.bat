@echo off
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" (
    echo Chua co .venv - chay build_exe.bat truoc.
    pause
    exit /b 1
)
".venv\Scripts\python.exe" tryon.py


@echo off
REM ============================================================================
REM  Copies a finished build (APK / EXE) into WebRTC\__Result.
REM  Usage (from any build script):  call "<path>\_copy_result.bat" "file" ["file2" ...]
REM ============================================================================
setlocal
set "RESULT=%~dp0..\__Result"
if not exist "%RESULT%" mkdir "%RESULT%"
for %%F in ("%RESULT%") do set "RESULT=%%~fF"
:next
if "%~1"=="" goto :eof
if exist "%~1" (
    copy /Y "%~1" "%RESULT%\" >nul && echo [result] Da copy: %RESULT%\%~nx1
) else (
    echo [result] Khong thay file: %~1
)
shift
goto next

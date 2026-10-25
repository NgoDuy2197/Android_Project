@echo off
REM ============================================================================
REM  Copies a finished build (APK / EXE) into WebRTC\__Result.
REM  Usage (from any build script):  call "<path>\_copy_result.bat" "file" ["file2" ...]
REM ============================================================================
setlocal
set "RESULT=%~dp0..\__Result"
if not exist "%RESULT%" mkdir "%RESULT%"
for %%F in ("%RESULT%") do set "RESULT=%%~fF"

REM --- Auto-backup the debug keystore so the OAuth SHA-1 stays the same across machines ---
set "KSDIR=%~dp0..\..\_keystore"
if not exist "%KSDIR%" mkdir "%KSDIR%"
if exist "%USERPROFILE%\.android\debug.keystore" (
    copy /Y "%USERPROFILE%\.android\debug.keystore" "%KSDIR%\debug.keystore" >nul && echo [keystore] Da sao luu debug.keystore -^> _keystore\
)

:next
if "%~1"=="" goto :eof
if exist "%~1" (
    copy /Y "%~1" "%RESULT%\" >nul && echo [result] Da copy: %RESULT%\%~nx1
) else (
    echo [result] Khong thay file: %~1
)
shift
goto next

@echo off
REM ============================================================================
REM  Build hub for every app under WebRTC\Mobile\
REM    build_all.bat                 -> build ALL projects that have build_apk.bat
REM    build_all.bat Toolbox         -> build only Toolbox
REM    build_all.bat Toolbox install -> build Toolbox then pass "install" to it
REM    build_all.bat list            -> just list buildable projects
REM  Each project keeps its own build_apk.bat; this only orchestrates them.
REM ============================================================================
setlocal enabledelayedexpansion
cd /d "%~dp0"
set "ONLY=%~1"
set "FWD=%~2"

if /I "%ONLY%"=="list" (
    echo [buildable projects]
    for /d %%D in (*) do if exist "%%D\build_apk.bat" echo   %%D
    goto :eof
)

set "OK="
set "FAIL="
set "SKIP="

if not "%ONLY%"=="" (
    if not exist "%ONLY%\build_apk.bat" (
        echo [ERROR] "%ONLY%" has no build_apk.bat. Run: build_all.bat list
        exit /b 1
    )
    call :build "%ONLY%"
    goto :report
)

for /d %%D in (*) do (
    if exist "%%D\build_apk.bat" ( call :build "%%D" ) else ( set "SKIP=!SKIP! %%~nxD" )
)
goto :report

:build
set "P=%~1"
echo.
echo ===========================================================
echo [BUILD] %P%
echo ===========================================================
pushd "%P%"
call build_apk.bat %FWD%
if errorlevel 1 ( set "FAIL=!FAIL! %~nx1" ) else ( set "OK=!OK! %~nx1" )
popd
goto :eof

:report
echo.
echo ===========================================================
echo  SUMMARY
echo ===========================================================
echo  OK     :!OK!
echo  FAILED :!FAIL!
echo  SKIPPED (no build_apk.bat):!SKIP!
if not "!FAIL!"=="" exit /b 1
goto :eof

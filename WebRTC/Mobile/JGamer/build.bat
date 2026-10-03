@echo off
REM ============================================================================
REM  JGamer (NES / SNES / PICO-8 emulator) - one-click APK builder for Windows
REM    build.bat            -> debug APKs; copies JGamer-arm64.apk, JGamer-arm32.apk
REM                            and JGamer.apk (= arm64) to root
REM    build.bat install    -> build + adb install to a connected device
REM    build.bat clean      -> clean outputs
REM  Auto-detects Android Studio's JDK (JBR) and the SDK.
REM ============================================================================
setlocal enabledelayedexpansion
cd /d "%~dp0"
set "GRADLEW=%~dp0gradlew.bat"
set "TASK=%~1"
if "%TASK%"=="" set "TASK=debug"

if not defined JAVA_HOME (
    for %%J in (
        "%ProgramFiles%\Android\Android Studio\jbr"
        "%ProgramFiles%\Android\Android Studio Preview\jbr"
        "%LOCALAPPDATA%\Programs\Android Studio\jbr"
    ) do if not defined JAVA_HOME if exist "%%~J\bin\java.exe" set "JAVA_HOME=%%~J"
)
if defined JAVA_HOME ( echo [build] JDK: !JAVA_HOME! ) else ( where java >nul 2>nul || ( echo [build] ERROR: No JDK found. Set JAVA_HOME. & exit /b 1 ) )

if not exist "local.properties" (
    if defined ANDROID_HOME (set "SDKDIR=%ANDROID_HOME%") else if defined ANDROID_SDK_ROOT (set "SDKDIR=%ANDROID_SDK_ROOT%") else set "SDKDIR=%LOCALAPPDATA%\Android\Sdk"
    set "SDKDIR=!SDKDIR:\=/!"
    > local.properties echo sdk.dir=!SDKDIR!
    echo [build] Wrote local.properties: sdk.dir=!SDKDIR!
)

if /I "%TASK%"=="clean" ( call "%GRADLEW%" clean & goto :eof )

REM ABI splits: one APK per ABI. arm64 = phones; arm32 (armeabi-v7a) = 32-bit TVs.
set "OUTDIR=app\build\outputs\apk\debug"
set "APK=%OUTDIR%\app-arm64-v8a-debug.apk"
set "APK32=%OUTDIR%\app-armeabi-v7a-debug.apk"

if /I "%TASK%"=="install" (
    call "%GRADLEW%" assembleDebug || goto :fail
    where adb >nul 2>nul && ( echo [build] Installing... & adb install -r "%APK%" ) || echo [build] adb not on PATH.
    goto :report
)

call "%GRADLEW%" assembleDebug || goto :fail

:report
echo.
if exist "%APK%" (
    copy /Y "%APK%" "%~dp0JGamer-arm64.apk" >nul
    REM JGamer.apk = arm64 copy, kept for existing habits / scripts.
    copy /Y "%APK%" "%~dp0JGamer.apk" >nul
    echo [build] SUCCESS. APK moi:
    echo         %~dp0JGamer-arm64.apk   [phones, 64-bit]
    echo         %~dp0JGamer.apk         [= arm64]
) else (
    echo [build] Built, but APK not found at %APK%
)
if exist "%APK32%" (
    copy /Y "%APK32%" "%~dp0JGamer-arm32.apk" >nul
    echo         %~dp0JGamer-arm32.apk   [32-bit TV, armeabi-v7a]
) else (
    echo [build] arm32 APK not found at %APK32%
)
goto :eof

:fail
echo.
echo [build] BUILD FAILED - see Gradle output above.
exit /b 1

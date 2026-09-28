@echo off
REM ============================================================================
REM  VoLTE Guard - one-click APK builder for Windows
REM    build.bat            -> debug APK; copies VoLTEGuard.apk to this folder
REM    build.bat install    -> build + adb install -r + grant permissions + launch
REM    build.bat grant      -> only grant WRITE_SECURE_SETTINGS / READ_PHONE_STATE via adb
REM    build.bat log        -> adb logcat filtered to VoLTEGuard
REM    build.bat clean      -> clean outputs
REM  Auto-detects Android Studio's JDK (JBR) and the SDK.
REM ============================================================================
setlocal enabledelayedexpansion
cd /d "%~dp0"
set "GRADLEW=%~dp0gradlew.bat"
set "PKG=com.dsoft.volteguard"
set "OUT=%~dp0VoLTEGuard.apk"
set "APK=app\build\outputs\apk\debug\app-debug.apk"
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

if defined ANDROID_HOME (set "SDKDIR=%ANDROID_HOME%") else if defined ANDROID_SDK_ROOT (set "SDKDIR=%ANDROID_SDK_ROOT%") else set "SDKDIR=%LOCALAPPDATA%\Android\Sdk"
if not exist "local.properties" (
    set "SDKF=!SDKDIR:\=/!"
    > local.properties echo sdk.dir=!SDKF!
    echo [build] Wrote local.properties: sdk.dir=!SDKF!
)
set "ADB=adb"
where adb >nul 2>nul || if exist "!SDKDIR!\platform-tools\adb.exe" set "ADB=!SDKDIR!\platform-tools\adb.exe"

if /I "%TASK%"=="clean" ( call "%GRADLEW%" clean & goto :eof )
if /I "%TASK%"=="grant" goto :grant
if /I "%TASK%"=="log" ( "!ADB!" logcat -s VoLTEGuard:* AndroidRuntime:E & goto :eof )

call "%GRADLEW%" assembleDebug || goto :fail

echo.
if not exist "%APK%" ( echo [build] Built, but APK not found at %APK% & exit /b 1 )
copy /Y "%APK%" "%OUT%" >nul
echo [build] SUCCESS. APK moi:
echo         %OUT%

if /I not "%TASK%"=="install" goto :eof
echo [build] Installing...
"!ADB!" install -r "%OUT%" || goto :fail

:grant
echo [build] Granting permissions via adb...
"!ADB!" shell pm grant %PKG% android.permission.WRITE_SECURE_SETTINGS
"!ADB!" shell pm grant %PKG% android.permission.READ_PHONE_STATE
"!ADB!" shell pm grant %PKG% android.permission.POST_NOTIFICATIONS
"!ADB!" shell cmd deviceidle whitelist +%PKG% >nul
if /I "%TASK%"=="install" "!ADB!" shell am start -n %PKG%/.MainActivity
goto :eof

:fail
echo.
echo [build] BUILD FAILED - see output above.
exit /b 1

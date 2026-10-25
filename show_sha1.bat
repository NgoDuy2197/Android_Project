@echo off
REM ============================================================================
REM  In SHA-1 / SHA-256 cua debug keystore (va release neu co) de dang ky OAuth.
REM ============================================================================
setlocal
cd /d "%~dp0"
if not defined JAVA_HOME (
    for %%J in ("%ProgramFiles%\Android\Android Studio\jbr" "%LOCALAPPDATA%\Programs\Android Studio\jbr") do if not defined JAVA_HOME if exist "%%~J\bin\keytool.exe" set "JAVA_HOME=%%~J"
)
set "KT=keytool"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\keytool.exe" set "KT=%JAVA_HOME%\bin\keytool.exe"

echo === DEBUG keystore (bang build_apk.bat) ===
set "DBG=%USERPROFILE%\.android\debug.keystore"
if exist "%~dp0_keystore\debug.keystore" set "DBG=%~dp0_keystore\debug.keystore"
"%KT%" -list -v -keystore "%DBG%" -alias androiddebugkey -storepass android -keypass android 2>nul | findstr /i "SHA1: SHA256:"

echo.
echo === RELEASE keystore trong _keystore (neu co) ===
for %%F in ("%~dp0_keystore\*.jks" "%~dp0_keystore\release.keystore") do if exist "%%F" (
    echo -- %%~nxF  (se hoi mat khau):
    "%KT%" -list -v -keystore "%%F" 2>nul | findstr /i "Alias SHA1: SHA256:"
)
pause

@echo off
REM ============================================================================
REM  Tao RELEASE keystore (de ky app phat hanh). Nhap thong tin, khoi can go lenh.
REM  File se luu vao _keystore\  -> HAY SAO LUU KY + nho mat khau (mat = khong update app duoc).
REM ============================================================================
setlocal enabledelayedexpansion
cd /d "%~dp0"

REM --- tim keytool (JBR cua Android Studio hoac JAVA_HOME) ---
if not defined JAVA_HOME (
    for %%J in (
        "%ProgramFiles%\Android\Android Studio\jbr"
        "%LOCALAPPDATA%\Programs\Android Studio\jbr"
    ) do if not defined JAVA_HOME if exist "%%~J\bin\keytool.exe" set "JAVA_HOME=%%~J"
)
set "KT=keytool"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\keytool.exe" set "KT=%JAVA_HOME%\bin\keytool.exe"
"%KT%" -help >nul 2>nul || ( echo [!] Khong tim thay keytool. Cai Android Studio hoac set JAVA_HOME. & pause & exit /b 1 )

set "OUTDIR=%~dp0_keystore"
if not exist "%OUTDIR%" mkdir "%OUTDIR%"

echo ============================================================
echo   TAO RELEASE KEYSTORE  (Ctrl+C de huy)
echo   (Luu y: mat khau se HIEN tren man hinh - lam o noi kin dao)
echo ============================================================
set /p FN=Ten file [release.jks]:
if "%FN%"=="" set "FN=release.jks"
set /p ALIAS=Alias (ten key) [dsoft]:
if "%ALIAS%"=="" set "ALIAS=dsoft"
set /p SP=Mat khau keystore:
if "%SP%"=="" ( echo [!] Phai co mat khau. & pause & exit /b 1 )
set /p KP=Mat khau key [enter=giong keystore]:
if "%KP%"=="" set "KP=%SP%"
set /p CN=Ten/to chuc (CN) [Dsoft]:
if "%CN%"=="" set "CN=Dsoft"
set /p YRS=So nam hieu luc [30]:
if "%YRS%"=="" set "YRS=30"
set /a DAYS=%YRS%*365

set "KSPATH=%OUTDIR%\%FN%"
if exist "%KSPATH%" ( echo [!] Da ton tai: %KSPATH% - chon ten khac. & pause & exit /b 1 )

"%KT%" -genkeypair -v -keystore "%KSPATH%" -alias "%ALIAS%" -keyalg RSA -keysize 2048 -validity %DAYS% -storepass "%SP%" -keypass "%KP%" -dname "CN=%CN%, O=%CN%, C=VN"
if errorlevel 1 ( echo [!] Tao that bai. & pause & exit /b 1 )

echo.
echo [OK] Da tao: %KSPATH%   (alias: %ALIAS%)
echo --- SHA-1 / SHA-256 (dung de dang ky OAuth client) ---
"%KT%" -list -v -keystore "%KSPATH%" -alias "%ALIAS%" -storepass "%SP%" | findstr /i "SHA1: SHA256: SHA-1: SHA-256:"
echo.
echo *** SAO LUU file %FN% + nho mat khau. Mat la KHONG the cap nhat app. ***
pause

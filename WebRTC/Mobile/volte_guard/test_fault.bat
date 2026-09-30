@echo off
REM ============================================================================
REM  VoLTE Guard - gia lap mat HD de test che do tu fix cua app
REM    test_fault.bat          -> menu
REM    test_fault.bat volte    -> tat cong tac VoLTE (khong can Shizuku)
REM    test_fault.bat carrier  -> lam mat carrier config VoLTE (can Shizuku dang chay)
REM    test_fault.bat restore  -> bat lai VoLTE thu cong neu app khong tu fix
REM
REM  SUB / CODE_* lay tu may Realme RMX2176 (Android 12). May/ROM khac thi chay
REM  muc 5 (detect) de xem gia tri dung roi sua 3 dong duoi.
REM ============================================================================
setlocal enabledelayedexpansion
set "SUB=6"
set "CODE_SET_VOLTE=220"
set "CODE_IMS_REG=144"
set "PKG=com.dsoft.volteguard"
set "WATCH_SEC=150"

set "ADB=adb"
where adb >nul 2>nul || set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
"%ADB%" get-state >nul 2>nul || ( echo [test] Khong thay thiet bi. Cam cap + bat USB debugging. & pause & exit /b 1 )

set "ACT=%~1"
if not "%ACT%"=="" goto :run

:menu
echo.
echo  ===== VoLTE Guard - test tu fix =====
call :imsstate
echo   1. Tat cong tac VoLTE        (khong can Shizuku)
echo   2. Lam mat carrier config    (can Shizuku)
echo   3. Bat lai VoLTE thu cong    (restore)
echo   4. Xem log app live
echo   5. Detect SUB / transaction code
echo   0. Thoat
set /p "C=Chon: "
if "%C%"=="1" set "ACT=volte"
if "%C%"=="2" set "ACT=carrier"
if "%C%"=="3" set "ACT=restore"
if "%C%"=="4" set "ACT=log"
if "%C%"=="5" set "ACT=detect"
if "%C%"=="0" exit /b 0
if "%ACT%"=="" goto :menu

:run
if /I "%ACT%"=="volte" (
    echo [test] Tat cong tac VoLTE subId=%SUB% ...
    "%ADB%" shell service call phone %CODE_SET_VOLTE% i32 %SUB% i32 0 <nul >nul
    goto :watch
)
if /I "%ACT%"=="carrier" (
    echo [test] Ghi de carrier_volte_available_bool=false qua app ^(Shizuku^) ...
    "%ADB%" shell am broadcast -n %PKG%/.DebugReceiver --es fault carrier_off <nul >nul
    goto :watch
)
if /I "%ACT%"=="restore" (
    echo [test] Bat lai cong tac VoLTE ...
    "%ADB%" shell service call phone %CODE_SET_VOLTE% i32 %SUB% i32 1 <nul >nul
    timeout /t 8 /nobreak >nul
    call :imsstate
    goto :end
)
if /I "%ACT%"=="log" ( "%ADB%" logcat -v time -s VoLTEGuard:* & goto :end )
if /I "%ACT%"=="detect" (
    "%ADB%" logcat -c
    "%ADB%" shell am broadcast -n %PKG%/.DebugReceiver --es fault codes <nul >nul
    timeout /t 2 /nobreak >nul
    "%ADB%" logcat -d -s VoLTEGuard:* | findstr /C:"fault=codes"
    goto :end
)
echo [test] Lenh khong hop le: %ACT%
goto :end

:watch
REM Mo cua so log rieng, cua so nay theo doi trang thai IMS.
"%ADB%" logcat -c
start "VoLTE Guard log" cmd /k ""%ADB%" logcat -v time -s VoLTEGuard:*"
echo [test] Da gay loi luc %TIME%. Theo doi toi da %WATCH_SEC%s (Ctrl+C de dung)...
set /a "LOST=0, T=0"
:loop
timeout /t 3 /nobreak >nul
set /a "T+=3"
"%ADB%" shell service call phone %CODE_IMS_REG% i32 %SUB% <nul | findstr /C:"00000000 00000001" >nul
if errorlevel 1 (
    set "LOST=1"
    echo   [!T!s] IMS: MAT
) else (
    if "!LOST!"=="1" (
        echo   [!T!s] IMS: DA DANG KY LAI  ^>^> APP TU FIX THANH CONG sau ~!T!s
        goto :end
    )
    echo   [!T!s] IMS: dang ky ^(chua rot^)
)
if !T! LSS %WATCH_SEC% goto :loop
echo.
echo [test] Het %WATCH_SEC%s ma IMS chua ve. Xem cua so log / chay: test_fault.bat restore
goto :end

:imsstate
"%ADB%" shell service call phone %CODE_IMS_REG% i32 %SUB% <nul | findstr /C:"00000000 00000001" >nul
if errorlevel 1 ( echo   Trang thai hien tai: IMS MAT ) else ( echo   Trang thai hien tai: IMS DA DANG KY ^(HD OK^) )
exit /b 0

:end
echo.
pause

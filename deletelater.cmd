@echo off
REM ============================================================
REM  Motoeye HUD - protocol version mismatch test harness
REM  Interactive menu for the 5 VersionCompat scenarios.
REM
REM  Assumes:
REM    emulator-5556 = phone (com.eried.eucplanet)
REM    emulator-5554 = HUD   (com.eried.eucplanet.hud)
REM    both APKs at PROTOCOL_MAJOR=1 PROTOCOL_MINOR=0
REM
REM  Delete after the version-mismatch UI ships to testers; this
REM  is dev-only scaffolding.
REM ============================================================

setlocal EnableDelayedExpansion

REM --- locate adb -----------------------------------------------------------
set "ADB="
for /f "delims=" %%i in ('where adb 2^>nul') do (
    set "ADB=%%i"
    goto :adb_found
)
if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" (
    set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
    goto :adb_found
)
if exist "%ANDROID_HOME%\platform-tools\adb.exe" (
    set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
    goto :adb_found
)
echo [error] could not find adb on PATH or in %%LOCALAPPDATA%%\Android\Sdk\platform-tools.
echo         set ANDROID_HOME or add platform-tools to PATH.
pause
exit /b 1

:adb_found
set "PHONE=emulator-5556"
set "HUD=emulator-5554"
set "PHONE_PKG=com.eried.eucplanet"
set "HUD_PKG=com.eried.eucplanet.hud"
set "PHONE_ACT=%PHONE_PKG%/.MainActivity"
set "HUD_ACT=%HUD_PKG%/com.eried.eucplanet.hud.HudActivity"

:menu
cls
echo ============================================================
echo  Motoeye HUD - VersionCompat test harness
echo ============================================================
echo   phone (5556) : %PHONE_PKG%
echo   HUD   (5554) : %HUD_PKG%
echo   adb          : %ADB%
echo ------------------------------------------------------------
call :show_props
echo ------------------------------------------------------------
echo  Test scenarios:
echo    1.  EXACT             (baseline, no mismatch)
echo    2.  HUD sees REMOTE_AHEAD_MAJOR   (phone is "future" 2.0)
echo    3.  HUD sees REMOTE_AHEAD_MINOR   (phone is "ahead" 1.5)
echo    4.  Phone sees REMOTE_AHEAD_MINOR (HUD is "ahead" 1.5)
echo    5.  Phone sees REMOTE_AHEAD_MAJOR (HUD is "future" 2.0)
echo.
echo    R.  Reset everything (clear all overrides, relaunch both)
echo    P.  Print current override props
echo    Q.  Quit
echo ============================================================
set "CHOICE="
set /p "CHOICE=Pick a test: "
if /i "%CHOICE%"=="1" goto :test_1
if /i "%CHOICE%"=="2" goto :test_2
if /i "%CHOICE%"=="3" goto :test_3
if /i "%CHOICE%"=="4" goto :test_4
if /i "%CHOICE%"=="5" goto :test_5
if /i "%CHOICE%"=="R" goto :reset
if /i "%CHOICE%"=="P" goto :print_props
if /i "%CHOICE%"=="Q" goto :end
echo.
echo  '%CHOICE%' is not a valid choice.
timeout /t 2 >nul
goto :menu

REM ------------------------------------------------------------
REM  TEST 1 - EXACT
REM ------------------------------------------------------------
:test_1
cls
echo ============================================================
echo  Test 1 - EXACT (baseline)
echo ============================================================
echo.
echo  Action: clears any leftover overrides on both devices, then
echo          restarts both apps so the wire link comes up clean.
echo.
echo  WHAT TO LOOK FOR
echo  ----------------
echo  HUD (5554):
echo    - live dashboard, telemetry updating, no warning anywhere.
echo.
echo  Phone (5556) - go to Settings -^> Integration -^> Motoeye HUD:
echo    - the install hint info banner reads "Need the HUD app?
echo      Get it at eucplanet.ried.no/hud" (no urgency).
echo.
call :clear_all
call :restart_both
echo.
echo  ...both apps restarted. The link should be up within a
echo  couple of seconds. Press a key to return to the menu.
pause >nul
goto :menu

REM ------------------------------------------------------------
REM  TEST 2 - HUD sees REMOTE_AHEAD_MAJOR
REM ------------------------------------------------------------
:test_2
cls
echo ============================================================
echo  Test 2 - HUD sees REMOTE_AHEAD_MAJOR
echo ============================================================
echo.
echo  Sets the phone to send protocolMajor=2 (faking a 2.0 phone
echo  paired with a 1.0 HUD).
echo.
echo  WHAT TO LOOK FOR
echo  ----------------
echo  HUD (5554):
echo    - full-screen dark overlay with a red PhonelinkOff icon
echo    - title  "Update HUD app"
echo    - subtitle  "Get the matching version at
echo                 eucplanet.ried.no/hud"
echo    - telemetry behind the overlay is FROZEN: the HUD drops
echo      the incoming frames because it can't trust them.
echo.
echo  Phone (5556): unaffected (phone still thinks it's at 1.0,
echo                so its UI shows EXACT).
echo.
echo  When done, run R from the menu to reset.
echo.
"%ADB%" -s %PHONE% shell setprop debug.eucplanet.proto.major 2
"%ADB%" -s %PHONE% shell setprop debug.eucplanet.proto.minor 0
call :restart_phone
echo  ...phone restarted with fake 2.0. Give it 3-5s to pair.
pause >nul
goto :menu

REM ------------------------------------------------------------
REM  TEST 3 - HUD sees REMOTE_AHEAD_MINOR
REM ------------------------------------------------------------
:test_3
cls
echo ============================================================
echo  Test 3 - HUD sees REMOTE_AHEAD_MINOR
echo ============================================================
echo.
echo  Sets the phone to send protocolMinor=5 (faking a 1.5 phone
echo  paired with a 1.0 HUD).
echo.
echo  WHAT TO LOOK FOR
echo  ----------------
echo  HUD (5554):
echo    - small orange/yellow pill near the TOP CENTER of the
echo      screen reading
echo      "Update available . eucplanet.ried.no/hud"
echo    - the rest of the dashboard is LIVE - this is a non-
echo      blocking hint. Rider keeps riding, just misses newer
echo      features.
echo.
echo  Phone (5556): unaffected.
echo.
"%ADB%" -s %PHONE% shell setprop debug.eucplanet.proto.major 1
"%ADB%" -s %PHONE% shell setprop debug.eucplanet.proto.minor 5
call :restart_phone
echo  ...phone restarted with fake 1.5. Give it 3-5s to pair.
pause >nul
goto :menu

REM ------------------------------------------------------------
REM  TEST 4 - Phone sees REMOTE_AHEAD_MINOR
REM ------------------------------------------------------------
:test_4
cls
echo ============================================================
echo  Test 4 - Phone sees REMOTE_AHEAD_MINOR
echo ============================================================
echo.
echo  Clears any phone override first, then sets the HUD to send
echo  hudProtocolMinor=5 (faking a 1.5 HUD paired with a 1.0
echo  phone). Phone reclassifies on the HUD's Pair greeting.
echo.
echo  WHAT TO LOOK FOR
echo  ----------------
echo  Phone (5556) - go to Settings -^> Integration -^> Motoeye HUD:
echo    - top banner inside the card changes from the install
echo      hint to a directional info banner:
echo      "The HUD is on a newer protocol. Some HUD features
echo       won't show until the phone app is updated from
echo       eucplanet.ried.no/hud"
echo    - styling is still info (surfaceVariant), not the red
echo      error variant - it's a soft hint.
echo.
echo  HUD (5554): unaffected (HUD still thinks it's at 1.0).
echo.
"%ADB%" -s %PHONE% shell setprop debug.eucplanet.proto.major """"
"%ADB%" -s %PHONE% shell setprop debug.eucplanet.proto.minor """"
call :restart_phone
"%ADB%" -s %HUD% shell setprop debug.eucplanet.proto.major 1
"%ADB%" -s %HUD% shell setprop debug.eucplanet.proto.minor 5
call :restart_hud
echo  ...phone reset, HUD set to fake 1.5. Give it 3-5s to pair,
echo  then navigate to Settings -^> Integration on the phone.
pause >nul
goto :menu

REM ------------------------------------------------------------
REM  TEST 5 - Phone sees REMOTE_AHEAD_MAJOR
REM ------------------------------------------------------------
:test_5
cls
echo ============================================================
echo  Test 5 - Phone sees REMOTE_AHEAD_MAJOR
echo ============================================================
echo.
echo  Sets the HUD to send hudProtocolMajor=2 (faking a 2.0 HUD
echo  paired with a 1.0 phone).
echo.
echo  WHAT TO LOOK FOR
echo  ----------------
echo  Phone (5556) - go to Settings -^> Integration -^> Motoeye HUD:
echo    - the banner switches to the RED errorContainer style
echo      with a Warning (triangle/!) icon
echo    - copy:
echo      "Phone app is too old for this HUD app. Update it from
echo       eucplanet.ried.no/hud to continue."
echo.
echo  HUD (5554): unaffected (HUD still on 1.0; it would only show
echo              "Update HUD app" if the PHONE went major-ahead).
echo.
"%ADB%" -s %PHONE% shell setprop debug.eucplanet.proto.major """"
"%ADB%" -s %PHONE% shell setprop debug.eucplanet.proto.minor """"
call :restart_phone
"%ADB%" -s %HUD% shell setprop debug.eucplanet.proto.major 2
"%ADB%" -s %HUD% shell setprop debug.eucplanet.proto.minor 0
call :restart_hud
echo  ...phone reset, HUD set to fake 2.0. Give it 3-5s to pair,
echo  then navigate to Settings -^> Integration on the phone.
pause >nul
goto :menu

REM ------------------------------------------------------------
REM  Reset
REM ------------------------------------------------------------
:reset
cls
echo ============================================================
echo  Reset - clear all overrides + restart both apps
echo ============================================================
call :clear_all
call :restart_both
echo.
echo  ...everything restored to truthful 1.0 on both sides.
pause
goto :menu

REM ------------------------------------------------------------
REM  Print current props
REM ------------------------------------------------------------
:print_props
cls
echo ============================================================
echo  Current override props
echo ============================================================
call :show_props_detailed
echo.
pause
goto :menu

REM ------------------------------------------------------------
REM  Helpers
REM ------------------------------------------------------------
:clear_all
echo  [reset] clearing override props on both devices
"%ADB%" -s %PHONE% shell setprop debug.eucplanet.proto.major """"
"%ADB%" -s %PHONE% shell setprop debug.eucplanet.proto.minor """"
"%ADB%" -s %HUD% shell setprop debug.eucplanet.proto.major """"
"%ADB%" -s %HUD% shell setprop debug.eucplanet.proto.minor """"
exit /b 0

:restart_phone
echo  [phone] force-stop + relaunch
"%ADB%" -s %PHONE% shell am force-stop %PHONE_PKG%
timeout /t 1 /nobreak >nul
"%ADB%" -s %PHONE% shell am start -n %PHONE_ACT% >nul
exit /b 0

:restart_hud
echo  [hud  ] force-stop + relaunch
"%ADB%" -s %HUD% shell am force-stop %HUD_PKG%
timeout /t 1 /nobreak >nul
"%ADB%" -s %HUD% shell am start -n %HUD_ACT% >nul
exit /b 0

:restart_both
call :restart_phone
call :restart_hud
exit /b 0

:show_props
REM Inline brief view that fits in the menu header.
for /f "delims=" %%a in ('"%ADB%" -s %PHONE% shell getprop debug.eucplanet.proto.major 2^>nul') do set "P_MAJ=%%a"
for /f "delims=" %%a in ('"%ADB%" -s %PHONE% shell getprop debug.eucplanet.proto.minor 2^>nul') do set "P_MIN=%%a"
for /f "delims=" %%a in ('"%ADB%" -s %HUD%   shell getprop debug.eucplanet.proto.major 2^>nul') do set "H_MAJ=%%a"
for /f "delims=" %%a in ('"%ADB%" -s %HUD%   shell getprop debug.eucplanet.proto.minor 2^>nul') do set "H_MIN=%%a"
if "%P_MAJ%"=="" set "P_MAJ=(unset)"
if "%P_MIN%"=="" set "P_MIN=(unset)"
if "%H_MAJ%"=="" set "H_MAJ=(unset)"
if "%H_MIN%"=="" set "H_MIN=(unset)"
echo   phone override : major=%P_MAJ%  minor=%P_MIN%
echo   HUD   override : major=%H_MAJ%  minor=%H_MIN%
exit /b 0

:show_props_detailed
call :show_props
echo.
echo  Interpretation:
echo    (unset) means the override is OFF and the device is
echo    sending its real PROTOCOL_MAJOR / PROTOCOL_MINOR (= 1/0).
echo    Any other value means the device sends that fake version
echo    on the wire instead. The receiving side classifies the
echo    fake against ITS real constants.
exit /b 0

:end
endlocal
exit /b 0

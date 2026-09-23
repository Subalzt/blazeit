@echo off
setlocal EnableDelayedExpansion
title Xoosh over USB

REM ---------------------------------------------------------------------------
REM Forwards a port on this PC to Xoosh on the phone, over the USB cable.
REM
REM Why bother when USB tethering also works: this way the browser reaches the
REM phone at http://localhost, and every browser treats localhost as a SECURE
REM CONTEXT even over plain HTTP. That switches on parallel downloads and the
REM real clipboard API, which a LAN address cannot do without a certificate.
REM
REM It also forwards a port rather than routing a network, so none of your
REM mobile data is shared with or consumed by this PC.
REM ---------------------------------------------------------------------------

set PORT=8787

where adb >nul 2>nul
if errorlevel 1 (
  if exist "%~dp0platform-tools\adb.exe" (
    set "ADB=%~dp0platform-tools\adb.exe"
  ) else (
    echo.
    echo   adb was not found.
    echo.
    echo   Download "SDK Platform-Tools for Windows" from
    echo   https://developer.android.com/tools/releases/platform-tools
    echo   and unzip it next to this file, so that
    echo   %~dp0platform-tools\adb.exe exists.
    echo.
    echo   It is a portable zip. Nothing is installed and no admin rights needed.
    echo.
    pause
    exit /b 1
  )
) else (
  set "ADB=adb"
)

echo.
echo   Looking for the phone...
"%ADB%" start-server >nul 2>nul
for /f "skip=1 tokens=1,2" %%a in ('"%ADB%" devices') do (
  if "%%b"=="device"       set FOUND=%%a
  if "%%b"=="unauthorized" set UNAUTH=%%a
)

if defined UNAUTH (
  echo.
  echo   The phone is connected but has not authorised this PC.
  echo   Unlock it and tap "Allow USB debugging".
  echo.
  pause
  exit /b 1
)

if not defined FOUND (
  echo.
  echo   No phone found. Check that:
  echo     1. the cable is a DATA cable, not charge-only
  echo     2. USB debugging is on   ^(Settings, Developer options^)
  echo     3. you tapped Allow on the phone
  echo.
  pause
  exit /b 1
)

echo   Found !FOUND!
"%ADB%" forward tcp:%PORT% tcp:%PORT% >nul
if errorlevel 1 (
  echo   Could not set up the forward.
  pause
  exit /b 1
)

echo.
echo   Xoosh is at  http://localhost:%PORT%
echo.
echo   Make sure Xoosh is running on the phone. It will ask you to allow this computer.
echo   Leave this window open. Closing it removes the forward.
echo.
start "" "http://localhost:%PORT%"

echo   Press any key to disconnect...
pause >nul
"%ADB%" forward --remove tcp:%PORT% >nul 2>nul
echo   Disconnected.

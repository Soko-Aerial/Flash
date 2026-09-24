@echo off
rem Double-click or run from cmd: builds the Flash APKs and Windows installers.
rem Arguments are passed through, e.g.  tools\build-release.cmd -Target android -Apk unsigned
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-release.ps1" %*
set RC=%ERRORLEVEL%
if "%~1"=="" pause
exit /b %RC%

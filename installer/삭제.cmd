@echo off
rem Veloster N dashboard uninstaller. Double-click to remove both apps from the car (install.ps1 -Uninstall).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" -Uninstall %*
echo.
pause

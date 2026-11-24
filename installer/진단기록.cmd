@echo off
rem Veloster N dashboard diagnostics. Reads the car's state and our apps' log into a file (install.ps1 -Diag). Changes nothing.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" -Diag %*
echo.
pause

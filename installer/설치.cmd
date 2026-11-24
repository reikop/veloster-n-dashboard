@echo off
rem Veloster N dashboard installer launcher. Double-click this file.
rem It runs install.ps1 through Windows' own signed powershell.exe (no SmartScreen block for an unsigned .exe).
rem Adapted from the i30 PD port's installer (sung9155/i30-pd-dashboard, MIT).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" %*
echo.
pause

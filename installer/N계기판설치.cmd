@echo off
rem Veloster N dashboard window installer. Double-click this file: one window, no console questions.
rem It starts gui.ps1 (WinForms) through Windows' own signed powershell.exe; gui.ps1 runs install.ps1 -Gui hidden.
rem The console installer (the other .cmd files) stays as the fallback.
start "" "%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -STA -ExecutionPolicy Bypass -WindowStyle Hidden -File "%~dp0gui.ps1"

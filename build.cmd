@echo off
rem ============================================================
rem  TY CourseTable - Windows build entry point
rem  (wraps build.ps1 so the PowerShell execution policy does not block it)
rem
rem  Usage:  build.cmd
rem          build.cmd -VersionName 7.13 -VersionCode 17
rem ============================================================
setlocal
set "PS=powershell.exe"
where pwsh.exe >nul 2>nul && set "PS=pwsh.exe"
"%PS%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1" %*
exit /b %ERRORLEVEL%

@echo off
REM Stop the Acme Demo platform containers (platform API, admin/demo web, platform DB).
REM Volumes are kept, so your demo data survives. Chatwoot, R2R and Ollama keep running
REM unless you run: powershell -File scripts\down.ps1 -IncludeUpstream
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\down.ps1"
echo.
if errorlevel 1 (echo Stop failed. See the messages above.) else (echo Platform stopped.)
pause

@echo off
REM Start the Acme Demo platform (Chatwoot, R2R, Ollama, platform API, admin/demo web).
REM Safe to run repeatedly. Does not delete containers or volumes.
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\up.ps1"
echo.
if errorlevel 1 (echo Start failed. See the messages above.) else (echo Platform started. Open http://localhost:4300/admin)
pause

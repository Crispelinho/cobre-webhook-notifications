@echo off
rem Starts the local webhook test receiver (scripts\test-webhook-receiver.py, port 9200)
rem and a cloudflared quick tunnel to it. It reads the tunnel host, sets
rem WEBHOOK_ALLOWED_HOSTS and opens a console with that variable for the service.
setlocal
cd /d "%~dp0"
set PORT=9200
set LOG=%TEMP%\cobre-tunnel-%PORT%.log
if exist "%LOG%" del "%LOG%" 2>nul

start "Receiver 9200" cmd /k python "%~dp0scripts\test-webhook-receiver.py"
start "Tunnel cloudflared" powershell -NoExit -Command "cloudflared tunnel --url http://127.0.0.1:%PORT% 2>&1 | Tee-Object -FilePath '%LOG%'"

echo Waiting for the tunnel URL (up to 60 s)...
for /f "delims=" %%H in ('powershell -NoProfile -Command "$r=''; for($i=0;$i -lt 60;$i++){ if(Test-Path '%LOG%'){ $m=Select-String -Path '%LOG%' -Pattern 'https://[a-z0-9-]+\.trycloudflare\.com' | Select-Object -First 1; if($m){ $r=$m.Matches[0].Value -replace 'https://',''; break } }; Start-Sleep 1 }; Write-Output $r"') do set WEBHOOK_ALLOWED_HOSTS=%%H

if not defined WEBHOOK_ALLOWED_HOSTS (
  echo Tunnel URL not found. Check the "Tunnel cloudflared" window.
  pause
  exit /b 1
)

echo.
echo WEBHOOK_ALLOWED_HOSTS=%WEBHOOK_ALLOWED_HOSTS%
echo A console with that variable opens now. Start the service from it with:
echo   gradlew.bat bootRun
echo Keep the "Receiver 9200" and "Tunnel cloudflared" windows open during the test.
start "Service" cmd /k "cd /d %~dp0"

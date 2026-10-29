@echo off
rem Levanta el receptor de pruebas (scripts\receptor-9200.py, puerto 9200) y un tunel
rem cloudflared hacia el. Lee el host del tunel, lo deja en WEBHOOK_ALLOWED_HOSTS y abre
rem una consola con esa variable puesta para arrancar la aplicacion desde ahi.
setlocal
cd /d "%~dp0"
set PORT=9200
set LOG=%TEMP%\cobre-tunel-%PORT%.log
if exist "%LOG%" del "%LOG%"

start "Receptor 9200" python "%~dp0scripts\receptor-9200.py"
start "Tunel cloudflared" powershell -NoExit -Command "cloudflared tunnel --url http://127.0.0.1:%PORT% 2>&1 | Tee-Object -FilePath '%LOG%'"

echo Esperando la URL del tunel (hasta 60 s)...
for /f "delims=" %%H in ('powershell -NoProfile -Command "$r=''; for($i=0;$i -lt 60;$i++){ if(Test-Path '%LOG%'){ $m=Select-String -Path '%LOG%' -Pattern 'https://[a-z0-9-]+\.trycloudflare\.com' | Select-Object -First 1; if($m){ $r=$m.Matches[0].Value -replace 'https://',''; break } }; Start-Sleep 1 }; Write-Output $r"') do set WEBHOOK_ALLOWED_HOSTS=%%H

if not defined WEBHOOK_ALLOWED_HOSTS (
  echo No se encontro la URL del tunel. Revisa la ventana "Tunel cloudflared".
  pause
  exit /b 1
)

echo.
echo WEBHOOK_ALLOWED_HOSTS=%WEBHOOK_ALLOWED_HOSTS%
echo Se abre una consola con esa variable. Arranca la aplicacion desde ella con:
echo   gradlew.bat bootRun
echo Mantén abiertas las ventanas "Receptor 9200" y "Tunel cloudflared" durante la prueba.
start "Aplicacion" cmd /k "cd /d %~dp0"

@echo off
setlocal
cd /d "%~dp0"
title health-agent stopper

echo ============================================================
echo   health-agent  -  stop all services
echo ============================================================
echo.

for %%P in (8080 8081 8001 5173) do (
  powershell -NoProfile -Command "$p=%%P; $c=Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue; if ($c) { $c | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue }; Write-Host ('  [stopped] port ' + $p) } else { Write-Host ('  [idle]    port ' + $p) }"
)

echo.
echo Done. You can close the service windows now.
pause

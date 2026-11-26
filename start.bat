@echo off
setlocal EnableExtensions
cd /d "%~dp0"
title health-agent launcher

REM Never let an inherited upstream URL bypass llm-gateway.
set "HA_LLM_BASE_URL="

echo ============================================================
echo   health-agent  -  one-click launcher
echo ============================================================
echo.

REM ---------- 1. Java ----------
call "_launch\env.bat"
if errorlevel 1 (
  echo [ERROR] Java not found.
  echo         Install JDK 21, or add java.exe to PATH.
  echo.
  pause
  exit /b 1
)
echo [OK] Java: "%JAVA_CMD%"

REM ---------- 2. jars ----------
if not exist "health-agent\target\health-agent-0.1.0-SNAPSHOT.jar" (
  echo [ERROR] Missing core jar: health-agent\target\health-agent-0.1.0-SNAPSHOT.jar
  echo         Build it first:  mvn clean package -DskipTests
  echo.
  pause
  exit /b 1
)
if not exist "llm-gateway\target\llm-gateway-0.1.0-SNAPSHOT.jar" (
  echo [ERROR] Missing gateway jar: llm-gateway\target\llm-gateway-0.1.0-SNAPSHOT.jar
  echo         Build it first:  mvn clean package -DskipTests
  echo.
  pause
  exit /b 1
)
echo [OK] jars found

REM ---------- 3. MySQL ----------
powershell -NoProfile -Command "$c=New-Object Net.Sockets.TcpClient; try { $c.Connect('127.0.0.1',3307); exit 0 } catch { exit 1 } finally { $c.Dispose() }"
if errorlevel 1 (
  echo [ERROR] MySQL is not reachable on 127.0.0.1:3307.
  echo         core refuses to start without it ^(by design, no silent fallback^).
  echo         Start MySQL, then run this script again.
  echo.
  pause
  exit /b 1
)
echo [OK] MySQL 3307

REM ---------- 4. ports ----------
call :port_busy 8080
if not errorlevel 1 (
  echo [ERROR] Port 8080 is already in use. Run stop.bat first.
  echo.
  pause
  exit /b 1
)
call :port_busy 8081
if not errorlevel 1 (
  echo [ERROR] Port 8081 is already in use. Run stop.bat first.
  echo.
  pause
  exit /b 1
)
echo [OK] ports 8080 / 8081 are free

REM ---------- 5. model key ----------
set "LLM_KEY=%HA_LLM_API_KEY%"
if not defined LLM_KEY (
  if exist "_launch\llm-key.txt" set /p LLM_KEY=<"_launch\llm-key.txt"
)

if defined LLM_KEY (
  echo [OK] API key found - ONLINE mode
  set "HA_LLM_API_KEY=%LLM_KEY%"
  set "HA_LLM_OFFLINE=false"
) else (
  echo [WARN] No API key found - OFFLINE mode.
  echo        Answers will be canned templates, no model calls.
  echo        To use a real model: copy _launch\llm-key.txt.example
  echo        to _launch\llm-key.txt and put your key inside.
  set "HA_LLM_OFFLINE=true"
)
echo.

REM ---------- 6. gateway ----------
if "%HA_LLM_OFFLINE%"=="false" (
  echo Starting llm-gateway on 8081 ...
  start "llm-gateway :8081" cmd /k "_launch\run-gateway.bat"
  call :wait_http 8081 /health 40
  if errorlevel 1 (
    echo [ERROR] llm-gateway did not become healthy within 40s.
    echo         Check the "llm-gateway" window for the real error.
    echo.
    pause
    exit /b 1
  )
  echo [OK] llm-gateway is up
) else (
  echo Skipping llm-gateway ^(offline mode^)
)

REM ---------- 7. core ----------
echo Starting health-agent core on 8080 ...
start "health-agent core :8080" cmd /k "_launch\run-core.bat"
call :wait_http 8080 /api/v1/routing/stats 90
if errorlevel 1 (
  echo [ERROR] core did not become healthy within 90s.
  echo         Check the "health-agent core" window for the real error.
  echo.
  pause
  exit /b 1
)
echo [OK] core is up

REM ---------- 8. knowledge (optional) ----------
if exist "knowledge-service\.venv\Scripts\python.exe" (
  echo Starting knowledge-service on 8001 ...
  start "knowledge-service :8001" cmd /k "_launch\run-knowledge.bat"
) else (
  echo [WARN] knowledge-service venv not found - skipped
)

REM ---------- 9. web (optional) ----------
set "WEB_STARTED=0"
if exist "web\package.json" (
  if exist "web\node_modules" (
    echo Starting web on 5173 ...
    start "web :5173" cmd /k "_launch\run-web.bat"
    set "WEB_STARTED=1"
  ) else (
    echo [WARN] web\node_modules missing - run "npm install" in web\ first, skipped
  )
) else (
  echo [WARN] web\package.json not found - skipped
)

REM ---------- 10. browser ----------
if "%WEB_STARTED%"=="1" (
  start "" powershell -NoProfile -WindowStyle Hidden -Command "Start-Sleep 6; Start-Process 'http://localhost:5173'"
) else (
  echo [INFO] web not started, browser not opened
)

echo.
echo ============================================================
echo   All set.
echo.
echo   web        http://localhost:5173
echo   core       http://localhost:8080
if "%HA_LLM_OFFLINE%"=="false" echo   gateway    http://localhost:8081
echo   knowledge  http://localhost:8001
echo.
echo   Each service runs in its own window.
echo   Run stop.bat to shut them all down.
echo ============================================================
pause
exit /b 0

REM ==================================================================

:port_busy
netstat -ano | findstr /R /C:":%~1 .*LISTENING" >nul
exit /b %errorlevel%

:wait_http
powershell -NoProfile -Command "$u='http://127.0.0.1:%~1%~2'; for($i=0;$i -lt %~3;$i++){ try { $r=Invoke-WebRequest -UseBasicParsing -Uri $u -TimeoutSec 2; if($r.StatusCode -eq 200){ exit 0 } } catch {}; Start-Sleep -Milliseconds 800 }; exit 1"
exit /b %errorlevel%

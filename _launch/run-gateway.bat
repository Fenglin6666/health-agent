@echo off
REM llm-gateway :8081
REM Holds the model credential. core never sees it.

cd /d "%~dp0.."
title llm-gateway :8081

call "%~dp0env.bat"
if errorlevel 1 (
  echo [ERROR] Java not found. Install JDK 21 or add java.exe to PATH.
  exit /b 1
)

if not defined HA_LLM_API_KEY (
  if exist "_launch\llm-key.txt" set /p HA_LLM_API_KEY=<"_launch\llm-key.txt"
)
if not defined HA_LLM_API_KEY (
  echo [ERROR] HA_LLM_API_KEY is not set.
  echo         Put your key into _launch\llm-key.txt, one line, nothing else.
  exit /b 1
)

echo Starting llm-gateway on 8081 ...
echo Upstream: %HA_LLM_UPSTREAM_URL%
echo.

"%JAVA_CMD%" -jar llm-gateway\target\llm-gateway-0.1.0-SNAPSHOT.jar --server.port=8081

echo.
echo llm-gateway exited with code %errorlevel%.

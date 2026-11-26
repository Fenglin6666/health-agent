@echo off
REM health-agent core :8080
REM Requires MySQL on 127.0.0.1:3307. Fails fast by design if it is down.

cd /d "%~dp0.."
title health-agent core :8080

call "%~dp0env.bat"
if errorlevel 1 (
  echo [ERROR] Java not found. Install JDK 21 or add java.exe to PATH.
  exit /b 1
)

REM Never inherit the upstream URL or the key:
REM core must go through llm-gateway, otherwise it bypasses the credential boundary.
set "HA_LLM_BASE_URL="
set "HA_LLM_API_KEY="

echo Starting core on 8080 ...
if "%HA_LLM_OFFLINE%"=="true" (
  echo Mode: OFFLINE ^(canned answers, no model calls^)
) else (
  echo Mode: ONLINE via llm-gateway
)
echo.

"%JAVA_CMD%" -jar health-agent\target\health-agent-0.1.0-SNAPSHOT.jar --server.port=8080

echo.
echo core exited with code %errorlevel%.

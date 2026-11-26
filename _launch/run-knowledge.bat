@echo off
REM knowledge-service :8001  (FastAPI / uvicorn)

cd /d "%~dp0..\knowledge-service"
title knowledge-service :8001

if not exist ".venv\Scripts\python.exe" (
  echo [ERROR] Python venv not found: knowledge-service\.venv
  echo.
  echo Create it with:
  echo   cd knowledge-service
  echo   python -m venv .venv
  echo   .venv\Scripts\python.exe -m pip install -r requirements.txt
  exit /b 1
)

echo Starting knowledge-service on 8001 ...
echo.

".venv\Scripts\python.exe" -m uvicorn app.main:app --port 8001

echo.
echo knowledge-service exited with code %errorlevel%.

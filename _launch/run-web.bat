@echo off
REM web :5173  (Vite dev server)

cd /d "%~dp0..\web"
title web :5173

where npm >nul 2>nul
if errorlevel 1 (
  echo [ERROR] npm not found. Install Node.js 18+ first.
  exit /b 1
)

if not exist "node_modules" (
  echo node_modules not found, running npm install ...
  call npm install
  if errorlevel 1 (
    echo [ERROR] npm install failed.
    exit /b 1
  )
)

echo Starting web on 5173 ...
echo.

call npm run dev

echo.
echo web exited with code %errorlevel%.

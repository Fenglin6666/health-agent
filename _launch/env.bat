@echo off
REM Resolve java.exe into JAVA_CMD.
REM Order: PATH first, then %USERPROFILE%\.jdks\* (IDEA-managed JDKs).
REM Returns 1 when nothing is found.

set "JAVA_CMD="

for /f "delims=" %%i in ('where java 2^>nul') do (
  if not defined JAVA_CMD set "JAVA_CMD=%%i"
)

if not defined JAVA_CMD (
  for /d %%D in ("%USERPROFILE%\.jdks\*") do (
    if not defined JAVA_CMD if exist "%%D\bin\java.exe" set "JAVA_CMD=%%D\bin\java.exe"
  )
)

if not defined JAVA_CMD exit /b 1
exit /b 0

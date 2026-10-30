@echo off
setlocal

set BASE_DIR=%~dp0

echo Installing ksp-sms-spring-boot-starter to local Maven repository...
mvn install:install-file ^
  -Dfile="%BASE_DIR%artifacts\ksp-sms-spring-boot-starter-1.4.0.jar" ^
  -DpomFile="%BASE_DIR%artifacts\pom.xml"

if errorlevel 1 (
  echo.
  echo Install failed.
  pause
  exit /b 1
)

echo.
echo Install completed successfully.
pause



@echo off
setlocal
cd /d "%~dp0"

call check-java.cmd
if errorlevel 1 exit /b 1

call mvnw.cmd clean package
if errorlevel 1 exit /b 1

set "PACKAGE_DIR=release\coding-agent"
set "PACKAGE_ZIP=release\coding-agent-windows.zip"

if exist "%PACKAGE_DIR%" rmdir /s /q "%PACKAGE_DIR%"
if exist "%PACKAGE_ZIP%" del /q "%PACKAGE_ZIP%"
mkdir "%PACKAGE_DIR%"

copy /y "target\coding-agent-0.0.1-SNAPSHOT.jar" "%PACKAGE_DIR%\coding-agent.jar" >nul
copy /y "start.cmd" "%PACKAGE_DIR%\start.cmd" >nul
copy /y "check-java.cmd" "%PACKAGE_DIR%\check-java.cmd" >nul
copy /y "application-local.example.yml" "%PACKAGE_DIR%\application-local.example.yml" >nul
copy /y "README.md" "%PACKAGE_DIR%\README.md" >nul

powershell.exe -NoProfile -Command "Compress-Archive -Path 'release\coding-agent\*' -DestinationPath 'release\coding-agent-windows.zip' -CompressionLevel Optimal"
if errorlevel 1 exit /b 1

echo Package created: release\coding-agent-windows.zip

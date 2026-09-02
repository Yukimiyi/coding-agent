@echo off
setlocal
cd /d "%~dp0"

call check-java.cmd
if errorlevel 1 exit /b 1

where jlink >nul 2>nul
if errorlevel 1 (
    echo Cannot build package: jlink was not found. Use a full JDK 21 or later.
    exit /b 1
)

call mvnw.cmd clean package
if errorlevel 1 exit /b 1

set "PACKAGE_DIR=target\release-package\coding-agent"
set "PACKAGE_ZIP=release\coding-agent-windows.zip"

if not exist "release" mkdir "release"
if errorlevel 1 (
    echo Cannot create package output directory: release
    exit /b 1
)

if exist "%PACKAGE_DIR%" rmdir /s /q "%PACKAGE_DIR%"
if exist "%PACKAGE_DIR%" (
    echo Cannot clean package staging directory: %PACKAGE_DIR%
    exit /b 1
)
if exist "%PACKAGE_ZIP%" del /q "%PACKAGE_ZIP%"
if exist "%PACKAGE_ZIP%" (
    echo Cannot replace package archive: %PACKAGE_ZIP%
    exit /b 1
)
mkdir "%PACKAGE_DIR%"
if errorlevel 1 exit /b 1

copy /y "target\coding-agent-0.0.1-SNAPSHOT.jar" "%PACKAGE_DIR%\coding-agent.jar" >nul
if errorlevel 1 exit /b 1
copy /y "start.cmd" "%PACKAGE_DIR%\start.cmd" >nul
if errorlevel 1 exit /b 1
copy /y "application-local.example.yml" "%PACKAGE_DIR%\application-local.example.yml" >nul
if errorlevel 1 exit /b 1
copy /y "README.md" "%PACKAGE_DIR%\README.md" >nul
if errorlevel 1 exit /b 1

echo Creating bundled Java runtime...
jlink --add-modules ALL-MODULE-PATH --strip-debug --no-header-files --no-man-pages --compress=2 --output "%PACKAGE_DIR%\runtime"
if errorlevel 1 exit /b 1

powershell.exe -NoProfile -Command "Compress-Archive -Path '%PACKAGE_DIR%\*' -DestinationPath '%PACKAGE_ZIP%' -CompressionLevel Optimal"
if errorlevel 1 exit /b 1

echo Package created: %PACKAGE_ZIP%

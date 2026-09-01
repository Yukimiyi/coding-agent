@echo off
setlocal
cd /d "%~dp0"

set "JAVA_COMMAND=%CD%\runtime\bin\java.exe"
if not exist "%JAVA_COMMAND%" (
    echo Bundled Java runtime not found: %JAVA_COMMAND%
    echo Run this script from the extracted Coding Agent package.
    echo For source development, use mvnw.cmd spring-boot:run.
    exit /b 1
)
set "JAVA_HOME=%CD%\runtime"
set "PATH=%CD%\runtime\bin;%PATH%"

set "JAR=coding-agent.jar"
if not exist "%JAR%" (
    echo Packaged application not found: %JAR%
    echo Re-extract the Coding Agent package and try again.
    exit /b 1
)

if not defined DEEPSEEK_API_KEY if not exist "application-local.yml" if not exist "src\main\resources\application-local.yml" (
    echo WARNING: Configure DEEPSEEK_API_KEY or create application-local.yml before running Agent tasks.
)

start "" /b powershell.exe -NoProfile -WindowStyle Hidden -Command ^
    "$deadline=(Get-Date).AddSeconds(60); do { try { $response=Invoke-WebRequest -UseBasicParsing 'http://127.0.0.1:8123/api/health' -TimeoutSec 2; if ($response.StatusCode -eq 200) { Start-Process 'http://127.0.0.1:8123/api/'; break } } catch {}; Start-Sleep -Milliseconds 500 } while ((Get-Date) -lt $deadline)"

"%JAVA_COMMAND%" -jar "%JAR%"

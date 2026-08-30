@echo off
setlocal
cd /d "%~dp0"

call check-java.cmd
if errorlevel 1 exit /b 1

set "JAR=coding-agent.jar"
if not exist "%JAR%" set "JAR=target\coding-agent-0.0.1-SNAPSHOT.jar"

if not exist "%JAR%" (
    echo Packaged application not found. Building it now...
    if not exist "mvnw.cmd" (
        echo Cannot build: mvnw.cmd is missing.
        exit /b 1
    )
    call mvnw.cmd -DskipTests package
    if errorlevel 1 exit /b 1
    set "JAR=target\coding-agent-0.0.1-SNAPSHOT.jar"
)

if not defined DEEPSEEK_API_KEY if not exist "application-local.yml" if not exist "src\main\resources\application-local.yml" (
    echo WARNING: Configure DEEPSEEK_API_KEY or create application-local.yml before running Agent tasks.
)

start "" /b powershell.exe -NoProfile -WindowStyle Hidden -Command ^
    "$deadline=(Get-Date).AddSeconds(60); do { try { $response=Invoke-WebRequest -UseBasicParsing 'http://127.0.0.1:8123/api/health' -TimeoutSec 2; if ($response.StatusCode -eq 200) { Start-Process 'http://127.0.0.1:8123/api/'; break } } catch {}; Start-Sleep -Milliseconds 500 } while ((Get-Date) -lt $deadline)"

java -jar "%JAR%"

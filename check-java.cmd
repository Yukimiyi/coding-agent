@echo off
setlocal

where java >nul 2>nul
if errorlevel 1 (
    echo Java 21 or later is required.
    exit /b 1
)

set "JAVA_MAJOR="
for /f "tokens=2 delims==" %%v in ('java -XshowSettings:properties -version 2^>^&1 ^| findstr "java.specification.version"') do for /f "tokens=1 delims=. " %%m in ("%%v") do set "JAVA_MAJOR=%%m"

if not defined JAVA_MAJOR (
    echo Cannot determine the installed Java version.
    exit /b 1
)

if %JAVA_MAJOR% LSS 21 (
    echo Java 21 or later is required. Check JAVA_HOME and PATH.
    exit /b 1
)

exit /b 0

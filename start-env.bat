@echo off
setlocal EnableExtensions
title PaiSmart Environment Starter

rem =====================================================================
rem  PaiSmart local environment starter (Windows)
rem
rem  Starts (skips services already listening, safe to re-run):
rem    1. MySQL        - Windows service "mysql" (usually already running)
rem    2. Redis        - 6379  (D:\Redis\Redis-x64-3.2.100)
rem    3. Elasticsearch- 9201  (D:\Elasticsearch\...  security disabled)
rem    4. MinIO        - 9000 / console 9001 (minioadmin / minioadmin)
rem    5. ZooKeeper    - 2181  (D:\kafka\kafka_2.13-3.9.0)
rem    6. Kafka        - 9092  (ZooKeeper mode)
rem    7. Frontend     - 9527  (pnpm dev) - optional
rem
rem  Backend is NOT started here: run SmartPaiApplication from IDEA
rem  (Spring Boot listens on http://localhost:8081).
rem
rem  Usage:
rem    start-env.bat                start infra + frontend
rem    start-env.bat -no-frontend   start only infra services
rem
rem  NOTE: paths below are machine-specific. Edit them if your install
rem  locations differ. Paths with spaces need extra quoting.
rem
rem  IMPORTANT: .bat services (ES/ZK/Kafka) are launched via
rem  `start "title" cmd /k "..."` - launching them as
rem  `start "title" "program.bat" arg` breaks the args under cmd.
rem =====================================================================

rem ---------------- machine-specific paths (edit if needed) -------------
set "REDIS_EXE=D:\Redis\Redis-x64-3.2.100\redis-server.exe"
set "ES_BAT=D:\Elasticsearch\elasticsearch-8.10.4-windows-x86_64\elasticsearch-8.10.4\bin\elasticsearch.bat"
set "MINIO_EXE=D:\MinIO\minio.exe"
set "MINIO_DATA=D:\MinIO\data"
set "KAFKA_HOME=D:\kafka\kafka_2.13-3.9.0"
set "FRONTEND_DIR=D:\javacode\paicongming\PaiSmart-main\PaiSmart-main\frontend"

rem ---------------- options -------------
set "START_FRONTEND=1"
if /i "%~1"=="-no-frontend" set "START_FRONTEND=0"
if /i "%~1"=="--no-frontend" set "START_FRONTEND=0"
if /i "%~1"=="-h"      goto :usage
if /i "%~1"=="--help"  goto :usage

echo [PaiSmart] Starting environment (backend runs from IDEA on 8081)...

rem =====================================================================
rem 1) MySQL (Windows service - usually already running)
rem =====================================================================
sc query mysql >nul 2>&1
if errorlevel 1 (
    echo [warn] MySQL service "mysql" not found. Please start MySQL manually.
) else (
    sc query mysql | findstr /i "RUNNING" >nul
    if not errorlevel 1 (
        echo [ok]   MySQL already running ^(3306^)
    ) else (
        echo [start] Starting MySQL service ...
        net start mysql >nul 2>&1
        if errorlevel 1 echo [warn] Could not start MySQL - run as ADMINISTRATOR
    )
)

rem =====================================================================
rem 2) Redis
rem =====================================================================
call :is_listening 6379
if not errorlevel 1 (
    echo [ok]   Redis already running ^(6379^)
) else (
    echo [start] Starting Redis ^(6379^) ...
    start "Redis" "%REDIS_EXE%" --port 6379 --bind 127.0.0.1
    call :wait_port 6379 20
)

rem =====================================================================
rem 3) Elasticsearch (now configured for http.port=9201 in elasticsearch.yml)
rem =====================================================================
call :is_listening 9201
if not errorlevel 1 (
    echo [ok]   Elasticsearch already running ^(9201^)
) else (
    echo [start] Starting Elasticsearch ^(9201^) ...
    start "Elasticsearch" "%ES_BAT%"
    call :wait_port 9201 180
)

rem =====================================================================
rem 4) MinIO (uploads bucket, minioadmin / minioadmin)
rem =====================================================================
call :is_listening 9000
if not errorlevel 1 (
    echo [ok]   MinIO already running ^(9000^)
) else (
    echo [start] Starting MinIO ^(9000^, console 9001^) ...
    start "MinIO" cmd /k "set MINIO_ROOT_USER=minioadmin&& set MINIO_ROOT_PASSWORD=minioadmin&& %MINIO_EXE% server %MINIO_DATA% --address :9000 --console-address :9001"
    call :wait_port 9000 30
)

rem =====================================================================
rem 5) ZooKeeper (required by Kafka)
rem =====================================================================
call :is_listening 2181
if not errorlevel 1 (
    echo [ok]   ZooKeeper already running ^(2181^)
) else (
    echo [start] Starting ZooKeeper ^(2181^) ...
    start "ZooKeeper" cmd /k ""%KAFKA_HOME%\bin\windows\zookeeper-server-start.bat" "%KAFKA_HOME%\config\zookeeper.properties""
    call :wait_port 2181 60
)

rem =====================================================================
rem 6) Kafka broker
rem =====================================================================
call :is_listening 9092
if not errorlevel 1 (
    echo [ok]   Kafka already running ^(9092^)
) else (
    echo [start] Starting Kafka ^(9092^) ...
    rem Clean any stale /brokers/ids left by a force-killed Kafka (avoids NodeExists)
    echo "deleteall /brokers/ids" | "%KAFKA_HOME%\bin\windows\zookeeper-shell.bat" localhost:2181 >nul 2>&1
    start "Kafka" cmd /k ""%KAFKA_HOME%\bin\windows\kafka-server-start.bat" "%KAFKA_HOME%\config\server.properties""
    call :wait_port 9092 120
)

rem =====================================================================
rem 7) Frontend (optional)
rem =====================================================================
if "%START_FRONTEND%"=="1" (
    call :is_listening 9527
    if not errorlevel 1 (
        echo [ok]   Frontend already running ^(9527^)
    ) else (
        echo [start] Starting Frontend Vite dev server ^(9527^) ...
        start "Frontend" cmd /k "cd /d %FRONTEND_DIR% && pnpm dev"
        call :wait_port 9527 60
    )
) else (
    echo [skip] Frontend not started ^(-no-frontend^)
)

rem =====================================================================
rem Summary
rem =====================================================================
echo.
echo ==================== PaiSmart environment status ====================
call :check_port 3306 "MySQL"
call :check_port 6379 "Redis"
call :check_port 9201 "Elasticsearch"
call :check_port 9000 "MinIO"
call :check_port 2181 "ZooKeeper"
call :check_port 9092 "Kafka"
if "%START_FRONTEND%"=="1" call :check_port 9527 "Frontend"
echo ====================================================================
echo.
echo Next steps:
echo   - Backend : start SmartPaiApplication in IDEA  -^> http://localhost:8081
echo   - Frontend: http://localhost:9527   (admin / admin123)
echo   - MinIO console: http://localhost:9001  (minioadmin / minioadmin)
echo   - NOTE: each app service runs in its own console window; the service
echo     stops when you close that window. MySQL is a Windows service.
echo   - Re-run this script any time - it skips services already running.
echo.
pause
exit /b 0

rem =====================================================================
rem Subroutines
rem =====================================================================

rem :is_listening <port>  -> errorlevel 0 if a process is listening
:is_listening
set "_lp=%~1"
netstat -ano | findstr /c:":%_lp% " | findstr /c:"LISTENING" >nul
exit /b %errorlevel%

rem :wait_port <port> <timeoutSeconds> -> waits until the port is up
:wait_port
set "_p=%~1"
set "_t=%~2"
set /a _elapsed=0
:wait_loop
call :is_listening %_p%
if not errorlevel 1 (
    echo [ready] port %_p% is listening
    exit /b 0
)
set /a _elapsed+=2
if %_elapsed% geq %_t% (
    echo [warn] port %_p% not listening within %_t%s - check its window
    exit /b 1
)
rem ~2s sleep (ping-based: works even when stdin is redirected)
ping -n 3 127.0.0.1 >nul
goto :wait_loop

rem :check_port <port> <name> -> prints status line
:check_port
call :is_listening %~1
if not errorlevel 1 (
    echo [ok]   %~2  %~1  running
) else (
    echo [down] %~2  %~1  NOT running
)
exit /b 0

:usage
echo Usage: start-env.bat [-no-frontend]
echo   (no args)            start all infra services + frontend
echo   -no-frontend         start only infra services (no pnpm dev)
exit /b 0

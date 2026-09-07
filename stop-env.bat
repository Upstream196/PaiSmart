@echo off
setlocal EnableExtensions
title PaiSmart Environment Stopper

rem =====================================================================
rem  PaiSmart environment stopper
rem  Stops all project services by the PID listening on each port:
rem    Backend 8081, Frontend 9527, Redis 6379, Elasticsearch 9201,
rem    MinIO 9000, ZooKeeper 2181, Kafka 9092.
rem  Also attempts to stop the MySQL Windows service (needs admin).
rem  Usage: stop-env.bat
rem =====================================================================

echo [PaiSmart] Stopping environment services...

for %%p in (8081 9527 6379 9201 9000 2181 9092) do call :kill_port %%p

rem ---- MySQL Windows service ----
echo.
sc query mysql >nul 2>&1
if errorlevel 1 (
    echo [skip] MySQL service "mysql" not found.
) else (
    sc query mysql | findstr /i "RUNNING" >nul
    if not errorlevel 1 (
        echo [mysql] Stopping MySQL service ...
        net stop mysql >nul 2>&1
        if errorlevel 1 (
            echo [warn] Could not stop MySQL service - run as ADMINISTRATOR
            echo        use: services.msc -^> stop "mysql"
        )
    ) else (
        echo [ok]   MySQL service already stopped
    )
)

echo.
echo [PaiSmart] Done. Run status-env.bat to verify.
echo.
pause
exit /b 0

rem :kill_port <port>  -> find PID listening on the port and kill it
:kill_port
set "_kp=%~1"
set "_kpid="
for /f "tokens=5" %%a in ('netstat -ano ^| findstr /c:":%_kp% " ^| findstr /c:"LISTENING"') do set "_kpid=%%a"
if defined _kpid (
    echo [stop] port %_kp%  pid %_kpid%
    taskkill /F /PID %_kpid% >nul 2>&1
) else (
    echo [ok]   port %_kp% not listening - nothing to stop
)
exit /b 0

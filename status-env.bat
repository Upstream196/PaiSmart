@echo off
setlocal EnableExtensions
title PaiSmart Environment Status

rem =====================================================================
rem  PaiSmart environment status checker
rem  Prints whether each service port is currently up.
rem  Usage: status-env.bat     close the window when done.
rem =====================================================================

echo ==================== PaiSmart environment status ====================
call :check_port 3306 "MySQL"
call :check_port 6379 "Redis"
call :check_port 9201 "Elasticsearch"
call :check_port 9000 "MinIO"
call :check_port 2181 "ZooKeeper"
call :check_port 9092 "Kafka"
call :check_port 8081 "Backend"
call :check_port 9527 "Frontend"
echo ====================================================================
echo.
echo Before starting the backend in IDEA, make sure Elasticsearch (9201)
echo is [ok]  -  the backend needs it at startup.
echo.
pause
exit /b 0

rem :is_listening <port>  -> errorlevel 0 if a process is listening
:is_listening
set "_lp=%~1"
netstat -ano | findstr /c:":%_lp% " | findstr /c:"LISTENING" >nul
exit /b %errorlevel%

rem :check_port <port> <name>  -> prints one status line
:check_port
call :is_listening %~1
if not errorlevel 1 (
    echo [ok]   %~2  %~1  running
) else (
    echo [down] %~2  %~1  NOT running
)
exit /b 0

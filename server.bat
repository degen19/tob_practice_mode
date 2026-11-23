@echo off
rem OpenRune: start once and leave this window open. It keeps the server running and rebuilds/restarts
rem it whenever the server shuts itself down (dropping .data\restart.flag restarts it after changes).
rem To stop for good: close this window.
title OpenRune server (leave open)
cd /d "%~dp0"
if exist "%~dp0..\jdk21\bin\java.exe" (
  set "JAVA_HOME=%~dp0..\jdk21"
  set "PATH=%~dp0..\jdk21\bin;%PATH%"
)
:loop
echo [%date% %time%] building and starting the server...
echo [%date% %time%] start > server.log
call gradlew.bat run --console=plain --no-configuration-cache >> server.log 2>&1
echo [%date% %time%] server stopped - restarting in 5 seconds (close this window to stop)
timeout /t 5 /nobreak > nul
goto loop

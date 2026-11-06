@echo off
rem ============================================================================
rem  Author: Role 5 - Timing & Integration Lead
rem  Purpose: ONE command to build and start the Smart Greenhouse Controller.
rem           No IDE needed. Finds Java 21 automatically if JAVA_HOME is not set.
rem  Usage:   run.bat
rem ============================================================================
setlocal
cd /d "%~dp0"
title Smart Greenhouse Controller - CLI

rem ---- 1. Find Java 21 (or newer) ---------------------------------------------
call :checkJava
if not defined JDK_OK (
    for /d %%D in ("%ProgramFiles%\Java\jdk-2*" "%ProgramFiles%\Eclipse Adoptium\jdk-2*" "%ProgramFiles%\Microsoft\jdk-2*") do (
        if exist "%%~D\bin\java.exe" set "JAVA_HOME=%%~D"
    )
    call :checkJava
)
if not defined JDK_OK (
    echo [run.bat] ERROR: Java 21 or newer was not found.
    echo [run.bat]        Install JDK 21 or set JAVA_HOME to the JDK folder.
    pause
    exit /b 1
)
echo [run.bat] Using Java: %JAVA_HOME%

rem ---- 2. Build (Maven wrapper - Maven does not need to be installed) ---------
echo [run.bat] Building the project ...
call "%~dp0mvnw.cmd" -q -DskipTests package
if errorlevel 1 (
    echo [run.bat] ERROR: build failed - see the messages above.
    pause
    exit /b 1
)

rem ---- 3. Start ---------------------------------------------------------------
title Smart Greenhouse Controller - CLI
if not exist logs mkdir logs
rem clear the window so this session starts on an empty screen; from here on the window
rem and the full log file logs\<date>\greenhouse_<time>.log contain exactly the same lines
cls
"%JAVA_HOME%\bin\java" -jar target\smart-greenhouse.jar
endlocal
exit /b 0

:checkJava
set "JDK_OK="
if not defined JAVA_HOME exit /b 0
if not exist "%JAVA_HOME%\release" exit /b 0
rem the release file contains e.g. JAVA_VERSION="21.0.12.1"  ( . matches the quote )
findstr /r /c:"JAVA_VERSION=.2[1-9]" "%JAVA_HOME%\release" >nul 2>&1 && set "JDK_OK=1"
exit /b 0

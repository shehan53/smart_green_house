@echo off
rem ============================================================================
rem  Author: Role 5 - Timing & Integration Lead
rem  Purpose: FULLY AUTOMATIC demo - no user action needed after starting it.
rem           1. starts the system in its own CLI window (watch the live logs there)
rem           2. waits until the system is ready
rem           3. runs ALL Postman scripts with Newman (setup, normal, F1-F5, X1-X4,
rem              repair, timing report, clean shutdown)
rem  Usage:   run-demo.bat          fast test run (about 2 minutes)
rem           run-demo.bat slow     presentation speed (+3 s per step, easy to follow)
rem  Needs:   Node.js (npx downloads Newman automatically the first time)
rem ============================================================================
setlocal
cd /d "%~dp0"
set "BASE=http://localhost:8080"
set "ENVFILE=postman\greenhouse.postman_environment.json"
if /i "%~1"=="slow" set "ENVFILE=postman\greenhouse-presentation.postman_environment.json"

echo [run-demo] Starting the system in a new window ...
start "Smart Greenhouse Controller - CLI" cmd /k ""%~dp0run.bat""

echo [run-demo] Waiting until the system is ready (first build can take a minute) ...
set /a TRIES=0
:wait
set /a TRIES+=1
curl -s -o nul "%BASE%/api/status" && goto ready
if %TRIES% GEQ 90 (
    echo [run-demo] ERROR: the system did not start within 3 minutes - check the CLI window.
    pause
    exit /b 1
)
ping -n 3 127.0.0.1 >nul
goto wait

:ready
echo [run-demo] System is up. Running all scripts automatically ...
if not exist reports mkdir reports
call npx --yes newman run postman\Greenhouse-Full-Demo.postman_collection.json ^
     -e "%ENVFILE%" ^
     -r cli,json --reporter-json-export reports\newman-report.json

echo.
echo [run-demo] Finished.
echo [run-demo]   Test results + response times : shown above, saved in reports\newman-report.json
echo [run-demo]   What the system did (per scenario): logs\^<date^>\events_^<time^>.log
echo [run-demo]   Everything incl. timing table    : the CLI window and logs\^<date^>\greenhouse_^<time^>.log
pause
endlocal

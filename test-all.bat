@echo off
rem ============================================================================
rem  Author: Role 5 - Timing & Integration Lead
rem  Purpose: Runs ALL Postman scripts automatically against the RUNNING system
rem           (start the system first with run.bat). The last script prints the
rem           timing report and shuts the system down cleanly.
rem  Usage:   test-all.bat
rem ============================================================================
setlocal
cd /d "%~dp0"
if not exist reports mkdir reports
call npx --yes newman run postman\Greenhouse-Full-Demo.postman_collection.json ^
     -e postman\greenhouse.postman_environment.json ^
     -r cli,json --reporter-json-export reports\newman-report.json
endlocal

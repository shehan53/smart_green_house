@echo off
rem ============================================================================
rem  Author: Role 5 - Timing & Integration Lead
rem  Purpose: Runs ONE Postman script automatically against the RUNNING system
rem           (start the system first with run.bat).
rem  Usage:   test-scenario.bat F1         (F1..F5, X1..X4, NORMAL, REPAIR, TIMING)
rem           test-scenario.bat F1 slow    presentation speed (+3 s per step)
rem ============================================================================
setlocal
cd /d "%~dp0"
set "ENVFILE=postman\greenhouse.postman_environment.json"
if /i "%~2"=="slow" set "ENVFILE=postman\greenhouse-presentation.postman_environment.json"
set "FOLDER="
if /i "%~1"=="F1"     set "FOLDER=F1-WrongValue"
if /i "%~1"=="F2"     set "FOLDER=F2-DeadSensor"
if /i "%~1"=="F3"     set "FOLDER=F3-SafeState"
if /i "%~1"=="F4"     set "FOLDER=F4-RaceCondition"
if /i "%~1"=="F5"     set "FOLDER=F5-HeatWave"
if /i "%~1"=="X1"     set "FOLDER=X1-InvalidInputs"
if /i "%~1"=="X2"     set "FOLDER=X2-StuckAndImpossibleValue"
if /i "%~1"=="X3"     set "FOLDER=X3-HumiditySoilFailure"
if /i "%~1"=="X4"     set "FOLDER=X4-MixedChaos"
if /i "%~1"=="NORMAL" set "FOLDER=01-Normal-Operation"
if /i "%~1"=="REPAIR" set "FOLDER=02-Repair-All"
if /i "%~1"=="TIMING" set "FOLDER=03-Timing-And-Shutdown"
if not defined FOLDER (
    echo Usage: test-scenario.bat F1^|F2^|F3^|F4^|F5^|X1^|X2^|X3^|X4^|NORMAL^|REPAIR^|TIMING [slow]
    exit /b 1
)
echo [test-scenario] Running %FOLDER% ...
call npx --yes newman run postman\Greenhouse-Full-Demo.postman_collection.json ^
     -e "%ENVFILE%" --folder "%FOLDER%"
endlocal

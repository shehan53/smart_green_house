#!/usr/bin/env sh
# ============================================================================
#  Author: Role 5 - Timing & Integration Lead
#  Purpose: ONE command to build and start the Smart Greenhouse Controller
#           (Linux / macOS / Git Bash). No IDE needed.
#  Usage:   sh run.sh
# ============================================================================
cd "$(dirname "$0")" || exit 1

if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA="$JAVA_HOME/bin/java"
else
  JAVA="java"
fi

if ! "$JAVA" -version 2>&1 | grep -Eq 'version "(2[1-9]|[3-9][0-9])'; then
  echo "[run.sh] ERROR: Java 21 or newer is required (set JAVA_HOME)."
  exit 1
fi

echo "[run.sh] Building the project ..."
sh ./mvnw -q -DskipTests package || { echo "[run.sh] ERROR: build failed"; exit 1; }

mkdir -p logs
echo "[run.sh] Starting the system - logs are also written to logs/<date>/greenhouse_<time>.log"
exec "$JAVA" -jar target/smart-greenhouse.jar

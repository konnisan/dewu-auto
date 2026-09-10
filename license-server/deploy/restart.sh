#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-/data/dewu-license}"
JAR_FILE="${JAR_FILE:-$APP_DIR/app.jar}"
PID_FILE="${PID_FILE:-$APP_DIR/app.pid}"
LOG_DIR="${LOG_DIR:-$APP_DIR/logs}"
LOG_FILE="${LICENSE_LOG_FILE:-$LOG_DIR/app.log}"
DB_PATH="${LICENSE_DB_PATH:-$APP_DIR/data/license.db}"
PORT="${LICENSE_PORT:-8080}"

mkdir -p "$LOG_DIR" "$(dirname "$DB_PATH")"

if [[ ! -f "$JAR_FILE" ]]; then
  echo "Jar not found: $JAR_FILE"
  exit 1
fi

if [[ -f "$PID_FILE" ]]; then
  OLD_PID=$(cat "$PID_FILE" || true)
  if [[ -n "${OLD_PID:-}" ]] && kill -0 "$OLD_PID" 2>/dev/null; then
    echo "Stopping PID $OLD_PID ..."
    kill "$OLD_PID"
    for _ in {1..20}; do
      if ! kill -0 "$OLD_PID" 2>/dev/null; then break; fi
      sleep 0.5
    done
    if kill -0 "$OLD_PID" 2>/dev/null; then
      echo "Process did not stop gracefully, forcing stop"
      kill -9 "$OLD_PID"
    fi
  fi
  rm -f "$PID_FILE"
fi

export LICENSE_DB_PATH="$DB_PATH"
export LICENSE_LOG_FILE="$LOG_FILE"
export LICENSE_PORT="$PORT"

nohup java -Xms128m -Xmx512m -jar "$JAR_FILE" >> "$LOG_FILE" 2>&1 &
NEW_PID=$!
echo "$NEW_PID" > "$PID_FILE"

sleep 2
if kill -0 "$NEW_PID" 2>/dev/null; then
  echo "Started dewu-license-server"
  echo "PID: $NEW_PID"
  echo "PORT: $PORT (127.0.0.1 only)"
  echo "DB: $DB_PATH"
  echo "LOG: $LOG_FILE"
else
  echo "Startup failed. Check: $LOG_FILE"
  exit 1
fi

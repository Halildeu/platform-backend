#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." &> /dev/null && pwd)
LOG_DIR="$ROOT_DIR/logs"
mkdir -p "$LOG_DIR"

run() {
  local name="$1"; shift
  local pom="$1"; shift
  local port="$1"; shift
  local log="$LOG_DIR/${name}.log"
  echo "[run] $name (port $port) → $log"
  # Eğer port kullanımda ise uyar
  if lsof -iTCP:"$port" -sTCP:LISTEN -Pn &>/dev/null; then
    echo "[warn] $name port $port already in use; skipping start" | tee -a "$log"
  else
    nohup "$ROOT_DIR/mvnw" -q -f "$pom" spring-boot:run >"$log" 2>&1 &
  fi
}

run auth-service        "$ROOT_DIR/auth-service/pom.xml"        8088
run discovery-server    "$ROOT_DIR/discovery-server/pom.xml"    8761
run permission-service  "$ROOT_DIR/permission-service/pom.xml"  8084
run user-service        "$ROOT_DIR/user-service/pom.xml"        8089
run variant-service     "$ROOT_DIR/variant-service/pom.xml"     8091
run api-gateway         "$ROOT_DIR/api-gateway/pom.xml"         8080

echo "[ok] Services starting. Tail logs with: tail -f logs/*.log"

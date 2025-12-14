#!/usr/bin/env bash
set -euo pipefail

ports=(8080 8084 8088 8089 8091)
for p in "${ports[@]}"; do
  if lsof -ti:"$p" -sTCP:LISTEN -Pn >/dev/null 2>&1; then
    pids=$(lsof -ti:"$p" -sTCP:LISTEN -Pn)
    echo "[stop] port $p → kill $pids"
    kill $pids || true
  else
    echo "[stop] port $p not listening"
  fi
done

echo "[ok] Stop signal sent. Some processes may take a few seconds to exit."


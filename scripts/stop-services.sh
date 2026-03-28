#!/usr/bin/env bash
set -euo pipefail

ports=(8080 8084 8088 8089 8091 8092 8761)
for p in "${ports[@]}"; do
  if lsof -ti:"$p" -sTCP:LISTEN -Pn >/dev/null 2>&1; then
    pids=$(lsof -ti:"$p" -sTCP:LISTEN -Pn)
    echo "[stop] port $p → kill $pids"
    kill $pids || true
  else
    echo "[stop] port $p not listening"
  fi
done

if [[ "${STOP_INFRA:-0}" == "1" ]]; then
  echo "[stop] STOP_INFRA=1, docker compose infra servisleri durduruluyor"
  docker compose -f "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." &> /dev/null && pwd)/docker-compose.yml" \
    stop postgres-db keycloak vault vault-unseal || true
fi

echo "[ok] Stop signal sent. Some processes may take a few seconds to exit."

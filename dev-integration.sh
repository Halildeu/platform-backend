#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"
echo "[integration] Starting backend in INTEGRATION mode (JWT enabled)"
export USER_SERVICE_PROFILES=docker,integration
export AUTH_SERVICE_PROFILES=docker,integration
export VARIANT_SERVICE_PROFILES=docker,integration
export CORE_DATA_SERVICE_PROFILES=docker,integration
export API_GATEWAY_PROFILES=docker,integration
export PERMISSION_SERVICE_PROFILES=docker,integration
export REPORT_SERVICE_PROFILES=docker,integration
docker compose up -d
echo ""
echo "[integration] Backend running in integration mode"
echo "  Keycloak: http://localhost:8081 (admin/admin)"
echo "  Gateway:  http://localhost:8080 (JWT required)"
echo "  Frontend: AUTH_MODE=keycloak VITE_AUTH_MODE=keycloak npx vite --port 3000"

#!/usr/bin/env bash
set -euo pipefail

# Verifies Vault and Keycloak health endpoints for CI/ops checks.
# Required env:
#   VAULT_ADDR                → e.g. https://vault-stage.example.com
#   KEYCLOAK_HEALTH_URL       → e.g. https://keycloak-stage.example.com/realms/serban/health
# Optional env:
#   VAULT_TOKEN               → if sys/health requires auth
#   KEYCLOAK_AUTH_HEADER      → custom Authorization header if auth is enabled

VAULT_ADDR="${VAULT_ADDR:?VAULT_ADDR required}"
KEYCLOAK_HEALTH_URL="${KEYCLOAK_HEALTH_URL:?KEYCLOAK_HEALTH_URL required}"

normalize_url() {
  local value="$1"
  value="${value%%/}"
  echo "$value"
}

VAULT_ADDR=$(normalize_url "$VAULT_ADDR")
KEYCLOAK_HEALTH_URL=$(normalize_url "$KEYCLOAK_HEALTH_URL")

echo "[health] Vault @ ${VAULT_ADDR}"
vault_headers=(-sSf -H 'Content-Type: application/json')
if [[ -n "${VAULT_TOKEN:-}" ]]; then
  vault_headers+=(-H "X-Vault-Token: ${VAULT_TOKEN}")
fi
vault_resp=$(curl "${vault_headers[@]}" "${VAULT_ADDR}/v1/sys/health")
python3 - <<'PY' <<<"$vault_resp"
import json, sys
data = json.load(sys.stdin)
print(f"  initialized={data.get('initialized')} sealed={data.get('sealed')} standby={data.get('standby')}")
PY

echo "[health] Keycloak @ ${KEYCLOAK_HEALTH_URL}"
kc_headers=(-sSf)
if [[ -n "${KEYCLOAK_AUTH_HEADER:-}" ]]; then
  kc_headers+=(-H "${KEYCLOAK_AUTH_HEADER}")
fi
curl "${kc_headers[@]}" "${KEYCLOAK_HEALTH_URL}" >/dev/null
echo "  HTTP 200 OK"

echo "[health] Identity endpoints reachable."

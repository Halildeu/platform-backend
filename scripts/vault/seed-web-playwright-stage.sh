#!/usr/bin/env bash
set -euo pipefail
set +x

# Seed "web-playwright" SSOT secrets into Vault KV v2 (stage).
# Dev-only usage is supported; do not log secret values.
#
# Required env:
#   PLAYWRIGHT_BASE_URL
#   KEYCLOAK_TOKEN_URL
#   KEYCLOAK_CLIENT_ID
#   KEYCLOAK_CLIENT_SECRET
#   KEYCLOAK_SCOPE
# Auth:
#   VAULT_TOKEN (preferred) or backend/.vault-dev/vault-init.json root_token (local dev convenience)
#
# Optional:
#   VAULT_ADDR (default: http://localhost:8200)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
DEV_INIT_FILE="${BACKEND_ROOT}/.vault-dev/vault-init.json"

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "[seed] HATA: '$1' komutu bulunamadı."
    exit 1
  fi
}

require_cmd curl
require_cmd jq

VAULT_ADDR="${VAULT_ADDR:-http://localhost:8200}"
VAULT_ADDR="${VAULT_ADDR%/}"

if [[ -z "${VAULT_TOKEN:-}" && -f "${DEV_INIT_FILE}" ]]; then
  VAULT_TOKEN="$(jq -r '.root_token // empty' "${DEV_INIT_FILE}" 2>/dev/null || true)"
fi

missing=()
[[ -n "${VAULT_TOKEN:-}" ]] || missing+=("VAULT_TOKEN")
[[ -n "${PLAYWRIGHT_BASE_URL:-}" ]] || missing+=("PLAYWRIGHT_BASE_URL")
[[ -n "${KEYCLOAK_TOKEN_URL:-}" ]] || missing+=("KEYCLOAK_TOKEN_URL")
[[ -n "${KEYCLOAK_CLIENT_ID:-}" ]] || missing+=("KEYCLOAK_CLIENT_ID")
[[ -n "${KEYCLOAK_CLIENT_SECRET:-}" ]] || missing+=("KEYCLOAK_CLIENT_SECRET")
[[ -n "${KEYCLOAK_SCOPE:-}" ]] || missing+=("KEYCLOAK_SCOPE")

if [[ "${#missing[@]}" -gt 0 ]]; then
  echo "[seed] STOP: missing keys: ${missing[*]}"
  exit 1
fi

config_payload="$(
  jq -n --arg baseUrl "${PLAYWRIGHT_BASE_URL}" \
    '{data:{PLAYWRIGHT_BASE_URL:$baseUrl}}'
)"

keycloak_payload="$(
  jq -n \
    --arg tokenUrl "${KEYCLOAK_TOKEN_URL}" \
    --arg clientId "${KEYCLOAK_CLIENT_ID}" \
    --arg clientSecret "${KEYCLOAK_CLIENT_SECRET}" \
    --arg scope "${KEYCLOAK_SCOPE}" \
    '{data:{KEYCLOAK_TOKEN_URL:$tokenUrl, KEYCLOAK_CLIENT_ID:$clientId, KEYCLOAK_CLIENT_SECRET:$clientSecret, KEYCLOAK_SCOPE:$scope}}'
)"

curl -sSf \
  -H "X-Vault-Token: ${VAULT_TOKEN}" \
  -H 'Content-Type: application/json' \
  -X POST "${VAULT_ADDR}/v1/secret/data/stage/web-playwright/config" \
  -d "${config_payload}" \
  >/dev/null

curl -sSf \
  -H "X-Vault-Token: ${VAULT_TOKEN}" \
  -H 'Content-Type: application/json' \
  -X POST "${VAULT_ADDR}/v1/secret/data/stage/web-playwright/keycloak" \
  -d "${keycloak_payload}" \
  >/dev/null

echo "[seed] WROTE keys: secret/stage/web-playwright/config: PLAYWRIGHT_BASE_URL"
echo "[seed] WROTE keys: secret/stage/web-playwright/keycloak: KEYCLOAK_TOKEN_URL KEYCLOAK_CLIENT_ID KEYCLOAK_CLIENT_SECRET KEYCLOAK_SCOPE"

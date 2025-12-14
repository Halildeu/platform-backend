#!/usr/bin/env bash
set -euo pipefail

# Writes minimal secrets to Vault KV for stage/prod.
# Usage: ENV=stage VAULT_ADDR=https://vault-stage.example.com VAULT_TOKEN=<token> ./scripts/vault/write-secrets-stage.sh

ENV_NAME="${ENV:-stage}"
VAULT_ADDR="${VAULT_ADDR:?VAULT_ADDR required}"
VAULT_TOKEN="${VAULT_TOKEN:?VAULT_TOKEN required}"
# Optional secrets: set via env when needed
SERVICE_CLIENT_USER_SERVICE_SECRET="${SERVICE_CLIENT_USER_SERVICE_SECRET:-}"
PERMISSION_OAUTH_CLIENT_SECRET="${PERMISSION_OAUTH_CLIENT_SECRET:-}"

echo "[vault] target=${VAULT_ADDR} env=${ENV_NAME}"

build_json() {
  python3 - "$@" <<'PY'
import json
import sys
args = sys.argv[1:]
data = {}
for i in range(0, len(args), 2):
    key = args[i]
    value = args[i + 1]
    if value:
        data[key] = value
print(json.dumps(data))
PY
}

kv_put() {
  local path="$1"; shift
  local json="$1"; shift || true
  curl -sSf \
    -H "X-Vault-Token: ${VAULT_TOKEN}" \
    -H 'Content-Type: application/json' \
    -X POST "${VAULT_ADDR}/v1/secret/data/${ENV_NAME}/${path}" \
    -d "{\"data\": ${json} }" >/dev/null
  echo "[vault] wrote secret/${ENV_NAME}/${path}"
}

# permission-service DB creds (provide via env; skip if empty)
perm_payload=$(build_json \
  PERMISSION_DB_USERNAME "${PERMISSION_DB_USERNAME:-}" \
  PERMISSION_DB_PASSWORD "${PERMISSION_DB_PASSWORD:-}")
if [[ "$perm_payload" != "{}" ]]; then
  kv_put permission-service "${perm_payload}"
else
  echo "[vault] skip permission-service DB creds (env empty)"
fi

# variant-service DB creds
variant_payload=$(build_json \
  VARIANT_DB_USERNAME "${VARIANT_DB_USERNAME:-}" \
  VARIANT_DB_PASSWORD "${VARIANT_DB_PASSWORD:-}")
if [[ "$variant_payload" != "{}" ]]; then
  kv_put variant-service "${variant_payload}"
else
  echo "[vault] skip variant-service DB creds (env empty)"
fi

# auth-service secrets (service token clients + optional JWT keys)
AUTH_JSON="{"
if [[ -n "${SERVICE_CLIENT_USER_SERVICE_SECRET}" ]]; then
  AUTH_JSON+="\"security.service-clients.user-service\": \"${SERVICE_CLIENT_USER_SERVICE_SECRET}\""
fi
if [[ -n "${SERVICE_JWT_PRIVATE_KEY_PATH:-}" && -n "${SERVICE_JWT_PUBLIC_KEY_PATH:-}" ]]; then
  priv=$(awk '{printf "%s\\n", $0}' "${SERVICE_JWT_PRIVATE_KEY_PATH}")
  pub=$(awk '{printf "%s\\n", $0}' "${SERVICE_JWT_PUBLIC_KEY_PATH}")
  AUTH_JSON+=", \"security.service-jwt.private-key\": \"${priv}\", \"security.service-jwt.public-key\": \"${pub}\", \"security.service-jwt.key-id\": \"${SERVICE_JWT_KEY_ID:-service-jwt-key}\""
fi
AUTH_JSON+="}"
if [[ "${AUTH_JSON}" != "{}" ]]; then
  kv_put auth-service "${AUTH_JSON}"
else
  echo "[vault] skip auth-service (no data provided)"
fi

# permission-service OAuth client-secret (optional)
if [[ -n "${PERMISSION_OAUTH_CLIENT_SECRET}" ]]; then
  kv_put permission-service/oauth "{\"client-id\":\"permission-service\",\"client-secret\":\"${PERMISSION_OAUTH_CLIENT_SECRET}\"}"
fi

echo "[vault] done"

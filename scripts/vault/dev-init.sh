#!/usr/bin/env bash
set -euo pipefail

# Quick Vault dev init: writes minimal keys to KV for services.
# Dev-only. Requires docker compose up vault (hashicorp/vault) with root token.

ENV_NAME="${ENV:-local}"
VAULT_ADDR_DEFAULT="http://localhost:8200"
VAULT_ADDR="${VAULT_ADDR:-$VAULT_ADDR_DEFAULT}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
INIT_FILE_DEFAULT="${BACKEND_ROOT}/.vault-dev/vault-init.json"
INIT_FILE="${INIT_FILE:-$INIT_FILE_DEFAULT}"

if [[ -z "${VAULT_TOKEN:-}" ]]; then
  if [[ -f "${INIT_FILE}" ]]; then
    set +x
    VAULT_TOKEN="$(jq -r '.root_token // empty' "${INIT_FILE}")"
    if [[ -z "${VAULT_TOKEN}" || "${VAULT_TOKEN}" == "null" ]]; then
      echo "[vault] HATA: init dosyasında root_token yok: ${INIT_FILE}"
      exit 1
    fi
  else
    echo "[vault] HATA: VAULT_TOKEN set değil ve init dosyası yok: ${INIT_FILE}"
    echo "[vault] Önce çalıştırın: bash scripts/vault/dev_init.sh && bash scripts/vault/dev_unseal.sh && bash scripts/vault/dev_enable_kv.sh"
    exit 1
  fi
fi

echo "[vault] Using VAULT_ADDR=${VAULT_ADDR} env=${ENV_NAME}"

curl_vault() {
  curl -sSf \
    -H "X-Vault-Token: ${VAULT_TOKEN}" \
    -H 'Content-Type: application/json' \
    "$@"
}

# KV v2 write helper: path is secret/<app>; body under { data: { ... } }
kv_put() {
  local path="$1"; shift
  local json="$1"; shift || true
  curl_vault -X POST "${VAULT_ADDR}/v1/secret/data/${ENV_NAME}/${path}" -d "{\"data\": ${json} }" >/dev/null
  echo "[vault] wrote secret/${ENV_NAME}/${path}"
}

# permission-service DB creds
kv_put permission-service '{
  "PERMISSION_DB_USERNAME": "postgres",
  "PERMISSION_DB_PASSWORD": "postgres"
}'

# auth-service service JWT keys (optional). If you have PEMs, pass env paths and the script will load them.
AUTH_CLIENT_SECRET="${SERVICE_CLIENT_USER_SERVICE_SECRET:-dev-secret}"
AUTH_JSON="{\"security.service-clients.user-service\": \"${AUTH_CLIENT_SECRET}\""
if [[ -n "${SERVICE_JWT_PRIVATE_KEY_PATH:-}" && -n "${SERVICE_JWT_PUBLIC_KEY_PATH:-}" ]]; then
  priv=$(awk '{printf "%s\\n", $0}' "${SERVICE_JWT_PRIVATE_KEY_PATH}")
  pub=$(awk '{printf "%s\\n", $0}' "${SERVICE_JWT_PUBLIC_KEY_PATH}")
  AUTH_JSON+=", \"security.service-jwt.private-key\": \"${priv}\", \"security.service-jwt.public-key\": \"${pub}\", \"security.service-jwt.key-id\": \"local-service-jwt\""
else
  echo "[vault] Skipping auth-service JWT keys (SERVICE_JWT_PRIVATE_KEY_PATH/PUBLIC not set)."
fi
AUTH_JSON+="}"
kv_put auth-service "${AUTH_JSON}"

echo "[vault] Done. You can verify with:"
echo "  curl -s -H 'X-Vault-Token: <token>' ${VAULT_ADDR}/v1/secret/metadata | jq"

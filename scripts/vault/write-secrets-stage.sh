#!/usr/bin/env bash
set -euo pipefail

# Writes per-service secrets to Vault KV2 paths expected by render-backend-env.sh.
#
# Vault path structure (aligned with render-backend-env.sh):
#   secret/<env>/db/auth-service        → { url, user, password }
#   secret/<env>/db/user-service        → { url, user, password }
#   secret/<env>/db/permission-service  → { url, user, password }
#   secret/<env>/db/variant-service     → { url, user, password }
#   secret/<env>/jwt/auth-service       → { privateKey, publicKey }
#
# Usage:
#   ENV=stage VAULT_ADDR=http://127.0.0.1:8200 VAULT_TOKEN=<token> \
#   POSTGRES_USER=postgres POSTGRES_PASSWORD=<pw> \
#   ./backend/scripts/vault/write-secrets-stage.sh

ENV_NAME="${ENV:-stage}"
VAULT_ADDR="${VAULT_ADDR:?VAULT_ADDR required}"
VAULT_TOKEN="${VAULT_TOKEN:?VAULT_TOKEN required}"

# Shared DB defaults — individual services can override via env
POSTGRES_HOST="${POSTGRES_HOST:-postgres-db}"
POSTGRES_PORT="${POSTGRES_PORT:-5432}"
POSTGRES_USER="${POSTGRES_USER:-}"
POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-}"

# Per-service DB overrides (if empty, falls back to shared)
AUTH_DB_HOST="${AUTH_DB_HOST:-${POSTGRES_HOST}}"
AUTH_DB_PORT="${AUTH_DB_PORT:-${POSTGRES_PORT}}"
AUTH_DB_NAME="${AUTH_DB_NAME:-auth_db}"
AUTH_DB_USER="${AUTH_DB_USER:-${POSTGRES_USER}}"
AUTH_DB_PASSWORD="${AUTH_DB_PASSWORD:-${POSTGRES_PASSWORD}}"

USER_DB_HOST="${USER_DB_HOST:-${POSTGRES_HOST}}"
USER_DB_PORT="${USER_DB_PORT:-${POSTGRES_PORT}}"
USER_DB_NAME="${USER_DB_NAME:-user_db}"
USER_DB_USER="${USER_DB_USER:-${POSTGRES_USER}}"
USER_DB_PASSWORD="${USER_DB_PASSWORD:-${POSTGRES_PASSWORD}}"

PERMISSION_DB_HOST="${PERMISSION_DB_HOST:-${POSTGRES_HOST}}"
PERMISSION_DB_PORT="${PERMISSION_DB_PORT:-${POSTGRES_PORT}}"
PERMISSION_DB_NAME="${PERMISSION_DB_NAME:-permission_db}"
PERMISSION_DB_USER="${PERMISSION_DB_USER:-${POSTGRES_USER}}"
PERMISSION_DB_PASSWORD="${PERMISSION_DB_PASSWORD:-${POSTGRES_PASSWORD}}"

VARIANT_DB_HOST="${VARIANT_DB_HOST:-${POSTGRES_HOST}}"
VARIANT_DB_PORT="${VARIANT_DB_PORT:-${POSTGRES_PORT}}"
VARIANT_DB_NAME="${VARIANT_DB_NAME:-variant_db}"
VARIANT_DB_USER="${VARIANT_DB_USER:-${POSTGRES_USER}}"
VARIANT_DB_PASSWORD="${VARIANT_DB_PASSWORD:-${POSTGRES_PASSWORD}}"

# JWT keys (optional — paths to PEM files)
SERVICE_JWT_PRIVATE_KEY_PATH="${SERVICE_JWT_PRIVATE_KEY_PATH:-}"
SERVICE_JWT_PUBLIC_KEY_PATH="${SERVICE_JWT_PUBLIC_KEY_PATH:-}"

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
  local path="$1"
  local json="$2"

  curl -sSf \
    -H "X-Vault-Token: ${VAULT_TOKEN}" \
    -H 'Content-Type: application/json' \
    -X POST "${VAULT_ADDR}/v1/secret/data/${ENV_NAME}/${path}" \
    -d "{\"data\": ${json} }" >/dev/null
  echo "[vault] wrote secret/${ENV_NAME}/${path}"
}

make_db_url() {
  local host="$1" port="$2" name="$3"
  printf 'jdbc:postgresql://%s:%s/%s' "${host}" "${port}" "${name}"
}

# --- DB credentials per service ---

auth_payload="$(build_json \
  url "$(make_db_url "${AUTH_DB_HOST}" "${AUTH_DB_PORT}" "${AUTH_DB_NAME}")" \
  user "${AUTH_DB_USER}" \
  password "${AUTH_DB_PASSWORD}")"
if [[ "${auth_payload}" != "{}" ]]; then
  kv_put "db/auth-service" "${auth_payload}"
else
  echo "[vault] skip db/auth-service (no data)"
fi

user_payload="$(build_json \
  url "$(make_db_url "${USER_DB_HOST}" "${USER_DB_PORT}" "${USER_DB_NAME}")" \
  user "${USER_DB_USER}" \
  password "${USER_DB_PASSWORD}")"
if [[ "${user_payload}" != "{}" ]]; then
  kv_put "db/user-service" "${user_payload}"
else
  echo "[vault] skip db/user-service (no data)"
fi

perm_payload="$(build_json \
  url "$(make_db_url "${PERMISSION_DB_HOST}" "${PERMISSION_DB_PORT}" "${PERMISSION_DB_NAME}")" \
  user "${PERMISSION_DB_USER}" \
  password "${PERMISSION_DB_PASSWORD}")"
if [[ "${perm_payload}" != "{}" ]]; then
  kv_put "db/permission-service" "${perm_payload}"
else
  echo "[vault] skip db/permission-service (no data)"
fi

variant_payload="$(build_json \
  url "$(make_db_url "${VARIANT_DB_HOST}" "${VARIANT_DB_PORT}" "${VARIANT_DB_NAME}")" \
  user "${VARIANT_DB_USER}" \
  password "${VARIANT_DB_PASSWORD}")"
if [[ "${variant_payload}" != "{}" ]]; then
  kv_put "db/variant-service" "${variant_payload}"
else
  echo "[vault] skip db/variant-service (no data)"
fi

# --- JWT keypair ---

if [[ -n "${SERVICE_JWT_PRIVATE_KEY_PATH}" && -n "${SERVICE_JWT_PUBLIC_KEY_PATH}" ]]; then
  if [[ -f "${SERVICE_JWT_PRIVATE_KEY_PATH}" && -f "${SERVICE_JWT_PUBLIC_KEY_PATH}" ]]; then
    priv="$(cat "${SERVICE_JWT_PRIVATE_KEY_PATH}")"
    pub="$(cat "${SERVICE_JWT_PUBLIC_KEY_PATH}")"
    jwt_payload="$(build_json privateKey "${priv}" publicKey "${pub}")"
    kv_put "jwt/auth-service" "${jwt_payload}"
  else
    echo "[vault] skip jwt/auth-service (key files not found)"
  fi
else
  echo "[vault] skip jwt/auth-service (paths not set)"
fi

echo "[vault] done"

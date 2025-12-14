#!/usr/bin/env bash
set -euo pipefail

normalize_base() {
  local input="$1"
  input="${input:-}"
  input="${input%%/}"
  echo "${input}"
}

AUTH_BASE_URL=$(normalize_base "${AUTH_BASE_URL:-http://localhost:8088}")
GATEWAY_BASE_URL=$(normalize_base "${GATEWAY_BASE_URL:-http://localhost:8080}")
OAUTH2_JWKS_URL="${OAUTH2_JWKS_URL:-${AUTH_BASE_URL}/oauth2/jwks}"

USER_JWT="${USER_JWT:-}"
if [[ -z "$USER_JWT" ]]; then
  echo "Usage: USER_JWT=<token> scripts/smoke.sh" 1>&2
  exit 1
fi

fetch_secret_from_vault() {
  local vault_addr="${VAULT_ADDR:-}"
  local vault_token="${VAULT_TOKEN:-}"
  local vault_env="${SERVICE_TOKEN_VAULT_ENV:-local}"
  local vault_service="${SERVICE_TOKEN_VAULT_SERVICE:-auth-service}"
  local vault_field="${SERVICE_TOKEN_VAULT_FIELD:-security.service-clients.user-service}"
  local python_bin="${PYTHON_BIN:-python3}"

  if [[ -z "$vault_addr" || -z "$vault_token" ]]; then
    return 0
  fi
  if ! command -v "$python_bin" >/dev/null 2>&1; then
    python_bin=python
  fi

  local response
  if ! response=$(curl -sSf \
      -H "X-Vault-Token: ${vault_token}" \
      -H 'Content-Type: application/json' \
      "${vault_addr}/v1/secret/data/${vault_env}/${vault_service}"); then
    echo "[smoke] WARN: Vault okuması başarısız (${vault_service})" >&2
    return 0
  fi

  local secret
  if ! secret=$(VAULT_FIELD="$vault_field" VAULT_SECRET_PAYLOAD="$response" "$python_bin" <<'PY' 2>/dev/null
import json
import os
field = os.environ["VAULT_FIELD"]
payload = os.environ.get("VAULT_SECRET_PAYLOAD", "")
try:
    parsed = json.loads(payload)
    data = parsed["data"]["data"]
    print(data.get(field, ""))
except Exception:
    print("", end="")
PY
    ); then
    echo "[smoke] WARN: Vault yanıtı parse edilemedi" >&2
    return 0
  fi
  echo -n "$secret"
}

SERVICE_TOKEN_CLIENT_ID="${SERVICE_TOKEN_CLIENT_ID:-user-service}"
SERVICE_TOKEN_CLIENT_SECRET="${SERVICE_TOKEN_CLIENT_SECRET:-}"

if [[ -z "$SERVICE_TOKEN_CLIENT_SECRET" ]]; then
  if ! SERVICE_TOKEN_CLIENT_SECRET="$(fetch_secret_from_vault)"; then
    SERVICE_TOKEN_CLIENT_SECRET=""
  fi
fi

if [[ -z "$SERVICE_TOKEN_CLIENT_SECRET" ]]; then
  SERVICE_TOKEN_CLIENT_SECRET="dev-secret"
  echo "[smoke] WARN: SERVICE_TOKEN_CLIENT_SECRET boş; dev-secret kullanılıyor." >&2
fi

BASIC_AUTH=$(printf "%s:%s" "$SERVICE_TOKEN_CLIENT_ID" "$SERVICE_TOKEN_CLIENT_SECRET" | base64 | tr -d '\n')
SERVICE_TOKEN_MINT_URL="${SERVICE_TOKEN_MINT_URL:-${AUTH_BASE_URL}/oauth2/token}"
SERVICE_TOKEN_AUDIENCE="${SERVICE_TOKEN_AUDIENCE:-permission-service}"
SERVICE_TOKEN_PERMISSIONS="${SERVICE_TOKEN_PERMISSIONS:-permissions:read}"

echo "[1/4] JWKS check"
curl -sf "${OAUTH2_JWKS_URL}" >/dev/null && echo "  ok" || { echo "  fail"; exit 1; }

echo "[2/4] Mint (client_credentials)"
curl -sf -X POST "${SERVICE_TOKEN_MINT_URL}" \
  -H "Authorization: Basic ${BASIC_AUTH}" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials&audience=${SERVICE_TOKEN_AUDIENCE}&permissions=${SERVICE_TOKEN_PERMISSIONS}" >/dev/null && echo "  ok" || echo "  warn (optional)"

echo "[3/4] Users list (via gateway)"
code=$(curl -s -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $USER_JWT" "${GATEWAY_BASE_URL}/api/users/all?page=1&pageSize=25")
echo "  HTTP $code"; [[ "$code" == "200" ]] || exit 1

echo "[4/4] Export guard (single request)"
code=$(curl -s -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $USER_JWT" "${GATEWAY_BASE_URL}/api/users/export.csv")
echo "  HTTP $code (200 expected; burst limit: $(printenv export.rate-limit.burst || echo 24))"

echo "[ok] Smoke done"

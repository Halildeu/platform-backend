#!/usr/bin/env bash
set -euo pipefail

ENV_NAME="${ENV:-stage}"
VAULT_ADDR="${VAULT_ADDR:?VAULT_ADDR required}"
VAULT_TOKEN="${VAULT_TOKEN:?VAULT_TOKEN required}"
VAULT_KV_MOUNT="${VAULT_KV_MOUNT:-secret}"
BACKEND_CONFIG_PATH_TEMPLATE="${BACKEND_CONFIG_PATH_TEMPLATE:-<env>/backend-deploy/config}"
BACKEND_GITHUB_PATH_TEMPLATE="${BACKEND_GITHUB_PATH_TEMPLATE:-<env>/ops/github/backend-deploy}"

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "[error] required command not found: $1" >&2
    exit 1
  fi
}

resolve_path() {
  local raw="$1"
  local resolved

  resolved="${raw//<env>/${ENV_NAME}}"
  resolved="${resolved#/}"
  printf '%s' "${resolved}"
}

kv_get_json() {
  local path="$1"
  local mount="$2"

  curl -sSf \
    -H "X-Vault-Token: ${VAULT_TOKEN}" \
    "${VAULT_ADDR%/}/v1/${mount}/data/${path}"
}

json_get() {
  local json="$1"
  local key="$2"
  printf '%s' "${json}" | python3 -c 'import json, sys; payload = json.load(sys.stdin); print(payload.get("data", {}).get("data", {}).get(sys.argv[1], "") or "", end="")' "${key}"
}

check_keys() {
  local label="$1"
  local mount="$2"
  local path="$3"
  local json="$4"
  shift 4
  local keys=("$@")
  local missing=()
  local found=()
  local key
  local value

  for key in "${keys[@]}"; do
    value="$(json_get "${json}" "${key}")"
    if [[ -n "${value}" ]]; then
      found+=("${key}")
    else
      missing+=("${key}")
    fi
  done

  echo "[check] ${label} path=${mount}/${path}"
  echo "[check] ${label} found=${found[*]:-none}"
  if [[ "${#missing[@]}" -gt 0 ]]; then
    echo "[check] ${label} missing=${missing[*]}"
    return 1
  fi

  echo "[check] ${label} ok"
}

main() {
  require_cmd curl
  require_cmd python3

  local mount
  local backend_config_path
  local backend_github_path
  local config_json
  local github_json
  local missing=0
  local ssh_enabled
  local config_required=(
    GIT_REMOTE_URL
    REPO_BRANCH
    GHCR_OWNER
    VAULT_URI
    VAULT_TOKEN
    KEYCLOAK_ISSUER_URI
    KEYCLOAK_JWKS_URI
    AUTH_VERIFICATION_BASE_URL
    AUTH_RESET_BASE_URL
    SERVICE_CLIENT_USER_SERVICE_SECRET
    PERMISSION_SERVICE_INTERNAL_API_KEY
    POSTGRES_USER
    POSTGRES_PASSWORD
    OPENFGA_STORE_ID
    OPENFGA_MODEL_ID
    CORE_DATA_DB_URL
    CORE_DATA_DB_USERNAME
    CORE_DATA_DB_PASSWORD
  )
  local github_base_required=(
    BACKEND_SSH_DEPLOY_ENABLED
  )
  local github_ssh_required=(
    BACKEND_DEPLOY_SSH_HOST
    BACKEND_DEPLOY_SSH_USER
    BACKEND_DEPLOY_SSH_KEY
    BACKEND_DEPLOY_SSH_KNOWN_HOSTS
    BACKEND_HEALTH_URLS
  )
  local github_optional=(
    BACKEND_DEPLOY_SSH_PORT
    BACKEND_DEPLOY_REMOTE_ENV_FILE
    BACKEND_DEPLOY_REMOTE_REPO_DIR
    BACKEND_DEPLOY_REMOTE_COMPOSE_PROFILES
  )
  local key
  local value
  local optional_found=()
  local optional_missing=()

  mount="${VAULT_KV_MOUNT#/}"
  mount="${mount%/}"
  backend_config_path="$(resolve_path "${BACKEND_CONFIG_PATH_TEMPLATE}")"
  backend_github_path="$(resolve_path "${BACKEND_GITHUB_PATH_TEMPLATE}")"

  config_json="$(kv_get_json "${backend_config_path}" "${mount}")"
  github_json="$(kv_get_json "${backend_github_path}" "${mount}")"

  if ! check_keys "backend-config(required)" "${mount}" "${backend_config_path}" "${config_json}" "${config_required[@]}"; then
    missing=1
  fi

  if ! check_keys "github-backend(required)" "${mount}" "${backend_github_path}" "${github_json}" "${github_base_required[@]}"; then
    missing=1
  fi

  ssh_enabled="$(json_get "${github_json}" "BACKEND_SSH_DEPLOY_ENABLED" | tr '[:upper:]' '[:lower:]')"
  if [[ "${ssh_enabled}" = "true" ]]; then
    if ! check_keys "github-backend(ssh-required)" "${mount}" "${backend_github_path}" "${github_json}" "${github_ssh_required[@]}"; then
      missing=1
    fi
  else
    echo "[check] github-backend ssh-required skipped because BACKEND_SSH_DEPLOY_ENABLED=${ssh_enabled:-empty}"
  fi

  for key in "${github_optional[@]}"; do
    value="$(json_get "${github_json}" "${key}")"
    if [[ -n "${value}" ]]; then
      optional_found+=("${key}")
    else
      optional_missing+=("${key}")
    fi
  done

  echo "[check] github-backend(optional) found=${optional_found[*]:-none}"
  if [[ "${#optional_missing[@]}" -gt 0 ]]; then
    echo "[check] github-backend(optional) missing=${optional_missing[*]}"
  fi

  if [[ "${missing}" = "1" ]]; then
    echo "[check] result=FAIL"
    exit 1
  fi

  echo "[check] result=PASS"
}

main "$@"

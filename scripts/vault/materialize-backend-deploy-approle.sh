#!/usr/bin/env bash
set -euo pipefail

ENV_NAME="${ENV:-stage}"
VAULT_ADDR="${VAULT_ADDR:?VAULT_ADDR required}"
VAULT_TOKEN="${VAULT_TOKEN:?VAULT_TOKEN required}"
VAULT_APPROLE_MOUNT="${VAULT_APPROLE_MOUNT:-auth/approle}"
VAULT_APPROLE_ROLE_NAME="${VAULT_APPROLE_ROLE_NAME:-backend-deploy-${ENV_NAME}}"
OUTPUT_DIR="${OUTPUT_DIR:-/home/halil/platform/state/vault/approle}"
ROLE_ID_FILE="${ROLE_ID_FILE:-${OUTPUT_DIR}/${VAULT_APPROLE_ROLE_NAME}.role-id}"
SECRET_ID_FILE="${SECRET_ID_FILE:-${OUTPUT_DIR}/${VAULT_APPROLE_ROLE_NAME}.secret-id}"

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "[error] required command not found: $1" >&2
    exit 1
  fi
}

json_get() {
  local key="$1"

  python3 -c 'import json, sys; payload = json.load(sys.stdin); print(payload.get("data", {}).get(sys.argv[1], "") or "", end="")' "${key}"
}

write_secret_file() {
  local path="$1"
  local value="$2"
  local dir
  local tmp_file

  dir="$(dirname "${path}")"
  mkdir -p "${dir}"
  chmod 700 "${dir}" 2>/dev/null || true

  tmp_file="$(mktemp "${dir}/.$(basename "${path}").XXXXXX")"
  chmod 600 "${tmp_file}"
  printf '%s' "${value}" > "${tmp_file}"
  mv "${tmp_file}" "${path}"
  chmod 600 "${path}"
}

main() {
  require_cmd curl
  require_cmd python3

  local mount
  local role_id_url
  local secret_id_url
  local role_id_response
  local secret_id_response
  local role_id
  local secret_id

  mount="${VAULT_APPROLE_MOUNT#/}"
  mount="${mount%/}"
  role_id_url="${VAULT_ADDR%/}/v1/${mount}/role/${VAULT_APPROLE_ROLE_NAME}/role-id"
  secret_id_url="${VAULT_ADDR%/}/v1/${mount}/role/${VAULT_APPROLE_ROLE_NAME}/secret-id"

  role_id_response="$(curl -sSf \
    -H "X-Vault-Token: ${VAULT_TOKEN}" \
    "${role_id_url}")"
  secret_id_response="$(curl -sSf \
    -H "X-Vault-Token: ${VAULT_TOKEN}" \
    -X POST "${secret_id_url}")"

  role_id="$(printf '%s' "${role_id_response}" | json_get role_id)"
  secret_id="$(printf '%s' "${secret_id_response}" | json_get secret_id)"

  if [[ -z "${role_id}" || -z "${secret_id}" ]]; then
    echo "[error] approle materialization returned an empty role_id or secret_id." >&2
    exit 1
  fi

  write_secret_file "${ROLE_ID_FILE}" "${role_id}"
  write_secret_file "${SECRET_ID_FILE}" "${secret_id}"

  echo "[approle] wrote ${ROLE_ID_FILE}"
  echo "[approle] wrote ${SECRET_ID_FILE}"
}

main "$@"

#!/usr/bin/env bash
set -euo pipefail

ENV_NAME="${ENV:-stage}"
VAULT_ADDR="${VAULT_ADDR:?VAULT_ADDR required}"
VAULT_TOKEN="${VAULT_TOKEN:?VAULT_TOKEN required}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
POLICY_DIR="${REPO_ROOT}/backend/infra/vault/policies"

RUNTIME_POLICY_TEMPLATE="${RUNTIME_POLICY_TEMPLATE:-${POLICY_DIR}/backend-deploy-runtime.hcl}"
ROTATION_POLICY_TEMPLATE="${ROTATION_POLICY_TEMPLATE:-${POLICY_DIR}/backend-deploy-rotation.hcl}"

RUNTIME_POLICY_NAME="${RUNTIME_POLICY_NAME:-backend-deploy-runtime-${ENV_NAME}}"
ROTATION_POLICY_NAME="${ROTATION_POLICY_NAME:-backend-deploy-rotation-${ENV_NAME}}"
ROLE_NAME="${ROLE_NAME:-backend-deploy-${ENV_NAME}}"
ROTATION_ROLE_NAME="${ROTATION_ROLE_NAME:-${ROLE_NAME}-rotation}"

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "[error] required command not found: $1" >&2
    exit 1
  fi
}

render_policy() {
  local template_path="$1"
  local out_path="$2"

  if [[ ! -f "${template_path}" ]]; then
    echo "[error] policy template missing: ${template_path}" >&2
    exit 1
  fi

  sed "s/{{env}}/${ENV_NAME}/g" "${template_path}" > "${out_path}"
}

ensure_approle_enabled() {
  if vault auth list -format=json | grep -q '"approle/"'; then
    echo "[vault] approle auth already enabled"
    return 0
  fi

  vault auth enable approle >/dev/null
  echo "[vault] enabled approle auth"
}

main() {
  require_cmd vault
  require_cmd mktemp
  require_cmd sed

  export VAULT_ADDR
  export VAULT_TOKEN

  ensure_approle_enabled

  local runtime_policy_file
  local rotation_policy_file
  runtime_policy_file="$(mktemp)"
  rotation_policy_file="$(mktemp)"

  trap 'rm -f "${runtime_policy_file}" "${rotation_policy_file}"' EXIT

  render_policy "${RUNTIME_POLICY_TEMPLATE}" "${runtime_policy_file}"
  render_policy "${ROTATION_POLICY_TEMPLATE}" "${rotation_policy_file}"

  vault policy write "${RUNTIME_POLICY_NAME}" "${runtime_policy_file}" >/dev/null
  echo "[vault] wrote policy ${RUNTIME_POLICY_NAME}"

  vault policy write "${ROTATION_POLICY_NAME}" "${rotation_policy_file}" >/dev/null
  echo "[vault] wrote policy ${ROTATION_POLICY_NAME}"

  vault write "auth/approle/role/${ROLE_NAME}" \
    token_policies="${RUNTIME_POLICY_NAME}" \
    token_ttl="24h" \
    token_max_ttl="72h" \
    secret_id_ttl="24h" \
    secret_id_num_uses=0 >/dev/null
  echo "[vault] wrote approle ${ROLE_NAME}"

  vault write "auth/approle/role/${ROTATION_ROLE_NAME}" \
    token_policies="${ROTATION_POLICY_NAME}" \
    token_ttl="15m" \
    token_max_ttl="30m" \
    secret_id_ttl="15m" \
    secret_id_num_uses=1 >/dev/null
  echo "[vault] wrote approle ${ROTATION_ROLE_NAME}"

  echo "[vault] next: retrieve role_id/secret_id manually and store them in the appropriate runtime secret store"
  echo "[vault] runtime role_id command: vault read -field=role_id auth/approle/role/${ROLE_NAME}/role-id"
  echo "[vault] runtime secret_id command: vault write -f auth/approle/role/${ROLE_NAME}/secret-id"
  echo "[vault] rotation role_id command: vault read -field=role_id auth/approle/role/${ROTATION_ROLE_NAME}/role-id"
  echo "[vault] rotation secret_id command: vault write -f auth/approle/role/${ROTATION_ROLE_NAME}/secret-id"
}

main "$@"

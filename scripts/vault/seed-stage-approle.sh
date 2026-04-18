#!/usr/bin/env bash
set -euo pipefail

# STORY-0319 PR #5: staging Vault AppRole kalıcı setup.
# Runbook: docs/04-operations/RUNBOOKS/RB-vault-approle-setup-stage.md
#
# Idempotent: policy + role mevcutsa re-write; secret-id her çalıştırmada
# yeni üretir (rotation). Script root token ile çalışır (staging host-local).
#
# Codex Thread A draft baseline + Claude implementer düzeltmeleri:
#   - Policy heredoc'ta ${kv_mount} typo düzeltildi
#   - write-backend-deploy-stage.sh'i çağırmadan önce mevcut KV config'i
#     export + merge (partial overwrite riski azaltma)
#   - AppRole login verify + revoke-self (short-lived token)

ENV_NAME="${ENV:-stage}"
VAULT_ADDR="${VAULT_ADDR:?VAULT_ADDR required}"
VAULT_TOKEN="${VAULT_TOKEN:?VAULT_TOKEN required}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
WRITE_BACKEND_DEPLOY_SCRIPT="${REPO_ROOT}/backend/scripts/vault/write-backend-deploy-stage.sh"
CANONICAL_ENV_FILE="${CANONICAL_ENV_FILE:-/home/halil/platform/env/backend.env}"

VAULT_KV_MOUNT="${VAULT_KV_MOUNT:-secret}"
VAULT_APPROLE_MOUNT="${VAULT_APPROLE_MOUNT:-auth/approle}"
ROLE_NAME="${VAULT_APPROLE_ROLE_NAME:-backend-deploy-${ENV_NAME}}"
POLICY_NAME="${VAULT_POLICY_NAME:-backend-deploy-${ENV_NAME}}"

SECRET_ID_TTL="${SECRET_ID_TTL:-768h}"
TOKEN_TTL="${TOKEN_TTL:-1h}"
TOKEN_MAX_TTL="${TOKEN_MAX_TTL:-4h}"
SECRET_ID_NUM_USES="${SECRET_ID_NUM_USES:-0}"

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || { echo "[error] missing command: $1" >&2; exit 1; }
}

normalize_mount() {
  local mount="$1"
  mount="${mount#/}"
  mount="${mount%/}"
  printf '%s' "${mount}"
}

backup_env_file() {
  local ts backup_path
  [[ -f "${CANONICAL_ENV_FILE}" ]] || { mkdir -p "$(dirname "${CANONICAL_ENV_FILE}")"; return 0; }
  ts="$(date -u +%Y%m%dT%H%M%SZ)"
  backup_path="${CANONICAL_ENV_FILE}.bak-${ts}-approle-seed"
  cp -a "${CANONICAL_ENV_FILE}" "${backup_path}"
  chmod 600 "${backup_path}" 2>/dev/null || true
  echo "[env] backup=${backup_path}"
}

upsert_env_value() {
  local file="$1" key="$2" value="$3" dir tmp_file
  dir="$(dirname "${file}")"
  mkdir -p "${dir}"
  chmod 700 "${dir}" 2>/dev/null || true
  tmp_file="$(mktemp "${dir}/.$(basename "${file}").XXXXXX")"
  chmod 600 "${tmp_file}"

  if [[ -f "${file}" ]]; then
    awk -v key="${key}" -v value="${value}" '
      BEGIN { updated = 0 }
      $0 ~ ("^" key "=") {
        if (!updated) {
          print key "=" value
          updated = 1
        }
        next
      }
      { print }
      END {
        if (!updated) {
          print key "=" value
        }
      }
    ' "${file}" > "${tmp_file}"
  else
    printf '%s=%s\n' "${key}" "${value}" > "${tmp_file}"
  fi

  mv "${tmp_file}" "${file}"
  chmod 600 "${file}" 2>/dev/null || true
}

ensure_approle_enabled() {
  local approle_path auth_list
  approle_path="$(normalize_mount "${VAULT_APPROLE_MOUNT}")"
  approle_path="${approle_path#auth/}"

  auth_list="$(vault auth list -format=json)"
  if printf '%s' "${auth_list}" | python3 -c "
import json, sys
data = json.load(sys.stdin)
mount = sys.argv[1] + '/'
sys.exit(0 if mount in data else 1)
" "${approle_path}" 2>/dev/null; then
    echo "[vault] approle auth already enabled at ${VAULT_APPROLE_MOUNT}"
    return 0
  fi

  vault auth enable -path="${approle_path}" approle >/dev/null
  echo "[vault] enabled approle auth at ${VAULT_APPROLE_MOUNT}"
}

write_policy() {
  local approle_mount kv_mount tmp_policy
  approle_mount="$(normalize_mount "${VAULT_APPROLE_MOUNT}")"
  kv_mount="$(normalize_mount "${VAULT_KV_MOUNT}")"
  tmp_policy="$(mktemp)"

  cat > "${tmp_policy}" <<EOF
path "${kv_mount}/data/${ENV_NAME}/backend-deploy/config" {
  capabilities = ["read"]
}

path "${kv_mount}/data/${ENV_NAME}/db/*" {
  capabilities = ["read"]
}

path "${kv_mount}/metadata/${ENV_NAME}/db/*" {
  capabilities = ["list"]
}

path "${approle_mount}/role/${ROLE_NAME}/role-id" {
  capabilities = ["read"]
}

path "${approle_mount}/role/${ROLE_NAME}/secret-id" {
  capabilities = ["update"]
}
EOF

  vault policy write "${POLICY_NAME}" "${tmp_policy}" >/dev/null
  rm -f "${tmp_policy}"
  echo "[vault] wrote policy ${POLICY_NAME}"
}

write_role() {
  local role_path
  role_path="$(normalize_mount "${VAULT_APPROLE_MOUNT}")/role/${ROLE_NAME}"
  vault write "${role_path}" \
    policies="${POLICY_NAME}" \
    secret_id_ttl="${SECRET_ID_TTL}" \
    token_ttl="${TOKEN_TTL}" \
    token_max_ttl="${TOKEN_MAX_TTL}" \
    secret_id_num_uses="${SECRET_ID_NUM_USES}" >/dev/null
  echo "[vault] wrote role ${ROLE_NAME}"
}

issue_role_material() {
  local role_base
  role_base="$(normalize_mount "${VAULT_APPROLE_MOUNT}")/role/${ROLE_NAME}"
  ROLE_ID="$(vault read -field=role_id "${role_base}/role-id")"
  SECRET_ID="$(vault write -field=secret_id -f "${role_base}/secret-id")"
  [[ -n "${ROLE_ID}" && -n "${SECRET_ID}" ]] || { echo "[error] empty role_id or secret_id" >&2; exit 1; }
  echo "[vault] issued new role_id + secret_id"
}

write_backend_deploy_kv() {
  # Mevcut KV config'i export et (partial overwrite riski azaltma)
  local existing_json mount
  mount="$(normalize_mount "${VAULT_KV_MOUNT}")"
  existing_json="$(curl -sS -H "X-Vault-Token: ${VAULT_TOKEN}" \
    "${VAULT_ADDR%/}/v1/${mount}/data/${ENV_NAME}/backend-deploy/config" \
    2>/dev/null || echo '')"

  if [[ -n "${existing_json}" ]] && printf '%s' "${existing_json}" | python3 -c '
import json, sys
data = json.load(sys.stdin)
if "data" in data and data["data"] is not None and "data" in data["data"]:
    sys.exit(0)
sys.exit(1)
' 2>/dev/null; then
    echo "[vault] existing KV config detected; merging with AppRole material"
    # Python ile export (shell quoting riskli)
    eval "$(printf '%s' "${existing_json}" | python3 -c "
import json, shlex, sys
payload = json.load(sys.stdin)['data']['data']
for k, v in payload.items():
    if v is None:
        continue
    t = str(v)
    if '\n' in t:
        continue  # multiline skip
    print(f'export {k}={shlex.quote(t)}')
")"
  else
    echo "[vault] no existing KV config; seeding fresh"
  fi

  # AppRole-first zorunlu alanlar
  export VAULT_URI="${VAULT_URI:-${VAULT_ADDR}}"
  export VAULT_AUTH_METHOD="APPROLE"
  export VAULT_ROLE_ID="${ROLE_ID}"
  export VAULT_SECRET_ID="${SECRET_ID}"
  export VAULT_SECRET_PREFIX="${VAULT_SECRET_PREFIX:-${ENV_NAME}}"
  export VAULT_FAIL_FAST="${VAULT_FAIL_FAST:-true}"
  export SPRING_CLOUD_VAULT_ENABLED="${SPRING_CLOUD_VAULT_ENABLED:-true}"
  export SPRING_CLOUD_VAULT_KV_ENABLED="${SPRING_CLOUD_VAULT_KV_ENABLED:-true}"
  export SPRING_CLOUD_VAULT_FAIL_FAST="${SPRING_CLOUD_VAULT_FAIL_FAST:-true}"

  ENV="${ENV_NAME}" VAULT_ADDR="${VAULT_ADDR}" VAULT_TOKEN="${VAULT_TOKEN}" \
    bash "${WRITE_BACKEND_DEPLOY_SCRIPT}"
}

update_canonical_env() {
  backup_env_file
  upsert_env_value "${CANONICAL_ENV_FILE}" "VAULT_URI" "${VAULT_ADDR}"
  upsert_env_value "${CANONICAL_ENV_FILE}" "VAULT_AUTH_METHOD" "APPROLE"
  upsert_env_value "${CANONICAL_ENV_FILE}" "VAULT_ROLE_ID" "${ROLE_ID}"
  upsert_env_value "${CANONICAL_ENV_FILE}" "VAULT_SECRET_ID" "${SECRET_ID}"
  upsert_env_value "${CANONICAL_ENV_FILE}" "VAULT_SECRET_PREFIX" "${ENV_NAME}"
  upsert_env_value "${CANONICAL_ENV_FILE}" "VAULT_FAIL_FAST" "true"
  upsert_env_value "${CANONICAL_ENV_FILE}" "SPRING_CLOUD_VAULT_ENABLED" "true"
  upsert_env_value "${CANONICAL_ENV_FILE}" "SPRING_CLOUD_VAULT_KV_ENABLED" "true"
  upsert_env_value "${CANONICAL_ENV_FILE}" "SPRING_CLOUD_VAULT_FAIL_FAST" "true"
  echo "[env] updated ${CANONICAL_ENV_FILE}"
}

verify_approle_login() {
  local mount login_url login_payload login_response approle_token probe_url probe_status
  mount="$(normalize_mount "${VAULT_APPROLE_MOUNT}")"
  login_url="${VAULT_ADDR%/}/v1/${mount}/login"
  probe_url="${VAULT_ADDR%/}/v1/${mount}/role/${ROLE_NAME}/role-id"

  # JSON payload build (Python, shell quoting safe)
  login_payload="$(python3 - "${ROLE_ID}" "${SECRET_ID}" <<'PY'
import json, sys
print(json.dumps({"role_id": sys.argv[1], "secret_id": sys.argv[2]}))
PY
)"
  login_response="$(curl -sSf -H 'Content-Type: application/json' -X POST "${login_url}" -d "${login_payload}")"
  approle_token="$(printf '%s' "${login_response}" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("auth",{}).get("client_token",""), end="")')"
  [[ -n "${approle_token}" ]] || { echo "[error] empty client_token" >&2; exit 1; }

  probe_status="$(curl -sS -o /dev/null -w '%{http_code}' \
    -H "X-Vault-Token: ${approle_token}" \
    "${probe_url}" || echo "000")"
  [[ "${probe_status}" == "200" ]] || { echo "[error] approle verify failed: ${probe_status}" >&2; exit 1; }

  # Short-lived token self-revoke (güvenlik: script sonunda token sızıntısı önleme)
  curl -sS -o /dev/null -H "X-Vault-Token: ${approle_token}" \
    -X POST "${VAULT_ADDR%/}/v1/auth/token/revoke-self" || true

  echo "[verify] AppRole login PASS (role-id read 200)"
}

main() {
  require_cmd vault
  require_cmd curl
  require_cmd python3
  require_cmd awk
  require_cmd mktemp
  require_cmd cp

  export VAULT_ADDR VAULT_TOKEN

  [[ -x "${WRITE_BACKEND_DEPLOY_SCRIPT}" ]] || chmod +x "${WRITE_BACKEND_DEPLOY_SCRIPT}" 2>/dev/null || true
  [[ -f "${WRITE_BACKEND_DEPLOY_SCRIPT}" ]] || { echo "[error] missing script: ${WRITE_BACKEND_DEPLOY_SCRIPT}" >&2; exit 1; }

  ensure_approle_enabled
  write_policy
  write_role
  issue_role_material
  write_backend_deploy_kv
  update_canonical_env
  verify_approle_login

  echo "[done] env=${ENV_NAME} role=${ROLE_NAME}"
  echo "[next] verify check: ENV=${ENV_NAME} VAULT_ADDR=${VAULT_ADDR} VAULT_TOKEN=<root> bash backend/scripts/vault/check-backend-deploy-stage.sh"
  echo "[next] rehearsal: gh workflow run deploy-backend.yml -f env=stage -f render_env_before_deploy=true"
}

main "$@"

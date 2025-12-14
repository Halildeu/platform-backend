#!/usr/bin/env bash
set -euo pipefail
set +x

# Dev-only: enable KV v2 at mount path "secret/".
# Not: Bu script secret değerlerini loglamaz.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
COMPOSE_FILE="${BACKEND_ROOT}/docker-compose.yml"

STATE_DIR="${BACKEND_ROOT}/.vault-dev"
INIT_FILE="${STATE_DIR}/vault-init.json"

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "[vault-dev] HATA: '$1' komutu bulunamadı."
    exit 1
  fi
}

require_cmd docker
require_cmd jq

if [ ! -f "${INIT_FILE}" ]; then
  echo "[vault-dev] HATA: init dosyası yok: ${INIT_FILE}"
  echo "[vault-dev] Önce çalıştırın: bash scripts/vault/dev_init.sh"
  exit 1
fi

docker compose -f "${COMPOSE_FILE}" up -d vault

status_json="$(docker compose -f "${COMPOSE_FILE}" exec -T vault vault status -address=http://127.0.0.1:8200 -format=json 2>/dev/null || true)"
sealed="$(jq -r '.sealed' <<<"${status_json}" 2>/dev/null || true)"
if [ "${sealed}" = "true" ]; then
  echo "[vault-dev] HATA: Vault sealed. Önce çalıştırın: bash scripts/vault/dev_unseal.sh"
  exit 1
fi
if [ "${sealed}" != "false" ]; then
  echo "[vault-dev] HATA: Vault sealed durumu okunamadı (sealed=${sealed:-<empty>})."
  exit 1
fi

root_token="$(jq -r '.root_token // empty' "${INIT_FILE}")"
if [ -z "${root_token}" ] || [ "${root_token}" = "null" ]; then
  echo "[vault-dev] HATA: init dosyasında root_token bulunamadı: ${INIT_FILE}"
  exit 1
fi

# Token arg olarak verilmez; stdin üzerinden login yapılır (-no-print).
printf '%s\n' "${root_token}" \
  | docker compose -f "${COMPOSE_FILE}" exec -T vault vault login -no-print -address=http://127.0.0.1:8200 - >/dev/null

mounts_json="$(docker compose -f "${COMPOSE_FILE}" exec -T vault vault secrets list -address=http://127.0.0.1:8200 -format=json)"

if jq -e '."secret/"' >/dev/null 2>&1 <<<"${mounts_json}"; then
  type="$(jq -r '."secret/".type // empty' <<<"${mounts_json}")"
  version="$(jq -r '."secret/".options.version // empty' <<<"${mounts_json}")"
  if [ "${type}" != "kv" ] || [ "${version}" != "2" ]; then
    echo "[vault-dev] HATA: secret/ mount'u KV v2 değil (type=${type} version=${version})."
    exit 1
  fi
  echo "[vault-dev] OK: secret/ zaten KV v2."
  exit 0
fi

docker compose -f "${COMPOSE_FILE}" exec -T vault vault secrets enable -address=http://127.0.0.1:8200 -path=secret kv-v2 >/dev/null
echo "[vault-dev] OK: KV v2 enable edildi (secret/)."

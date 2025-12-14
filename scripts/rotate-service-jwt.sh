#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "Kullanım: $0 <env> <kid>" >&2
  exit 1
fi

if [[ -z "${VAULT_ADDR:-}" || -z "${VAULT_TOKEN:-}" ]]; then
  echo "VAULT_ADDR ve VAULT_TOKEN ayarlanmalı" >&2
  exit 1
fi

ENV="$1"
KEY_ID="$2"
OUTPUT_DIR=${OUTPUT_DIR:-/tmp}

PRIVATE_KEY="${OUTPUT_DIR}/service-jwt-${KEY_ID}-private.pem"
PUBLIC_KEY="${OUTPUT_DIR}/service-jwt-${KEY_ID}-public.pem"

openssl genrsa -out "${PRIVATE_KEY}" 2048 >/dev/null 2>&1
openssl pkcs8 -topk8 -nocrypt -in "${PRIVATE_KEY}" -out "${PRIVATE_KEY}" >/dev/null 2>&1
openssl rsa -in "${PRIVATE_KEY}" -pubout -out "${PUBLIC_KEY}" >/dev/null 2>&1

vault kv put "secret/${ENV}/auth-service/service-jwt" \
  private-key=@"${PRIVATE_KEY}" \
  public-key=@"${PUBLIC_KEY}" \
  key-id="${KEY_ID}"

echo "Yeni anahtar yüklendi: secret/${ENV}/auth-service/service-jwt (kid=${KEY_ID})"
echo "JWKS endpointi güncellendiğinde servisleri rolling restart ile yenileyin."

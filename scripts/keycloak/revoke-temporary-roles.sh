#!/usr/bin/env bash
set -euo pipefail

: "${KEYCLOAK_BASE_URL:?KEYCLOAK_BASE_URL yok}";
: "${KEYCLOAK_REALM:?KEYCLOAK_REALM yok}";
: "${KEYCLOAK_CLIENT_ID:?KEYCLOAK_CLIENT_ID yok}";
: "${KEYCLOAK_CLIENT_SECRET:?KEYCLOAK_CLIENT_SECRET yok}";

SIEM_WEBHOOK_URL=${SIEM_WEBHOOK_URL:-}
SIEM_WEBHOOK_AUTH=${SIEM_WEBHOOK_AUTH:-}

TOKEN=$(curl -s -X POST "${KEYCLOAK_BASE_URL}/realms/serban/protocol/openid-connect/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d "grant_type=client_credentials&client_id=${KEYCLOAK_CLIENT_ID}&client_secret=${KEYCLOAK_CLIENT_SECRET}" | jq -r '.access_token')

if [[ -z "${TOKEN}" || "${TOKEN}" == "null" ]]; then
  echo "Failed to obtain Keycloak token" >&2
  exit 1
fi

RESPONSE=$(curl -s -X POST "${KEYCLOAK_BASE_URL}/admin/realms/${KEYCLOAK_REALM}/users/roles-temporary" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H 'Content-Type: application/json')

TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
LOG_ENTRY="[${TIMESTAMP}] keycloak temporary roles revoked | payload=${RESPONSE}"
echo "${LOG_ENTRY}" >> /var/log/keycloak/revoke-temporary-roles.log

if [[ -n "${SIEM_WEBHOOK_URL}" ]]; then
  SIEM_PAYLOAD=$(jq -n --arg timestamp "${TIMESTAMP}" --arg payload "${RESPONSE}" '{eventType: "keycloak_roles_revoked", timestamp: $timestamp, raw: $payload}')
  CURL_ARGS=(-s -X POST "${SIEM_WEBHOOK_URL}" -H 'Content-Type: application/json' -d "${SIEM_PAYLOAD}")
  if [[ -n "${SIEM_WEBHOOK_AUTH}" ]]; then
    CURL_ARGS+=(-H "Authorization: ${SIEM_WEBHOOK_AUTH}")
  fi
  if ! curl "${CURL_ARGS[@]}" >/dev/null; then
    echo "SIEM webhook post failed" >&2
  fi
fi

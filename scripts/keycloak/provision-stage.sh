#!/usr/bin/env bash
set -euo pipefail

# Provision Keycloak realm, client scopes (audience) and service clients.
# Optionally writes generated client secrets to Vault KV under secret/<ENV>/<service>/oauth/*
#
# Requirements:
#  - Keycloak reachable (admin user/pass)
#  - curl + jq available
#  - For Vault write: VAULT_ADDR + VAULT_TOKEN set (optional)
#
# Usage example:
#   ENV=stage \
#   KEYCLOAK_BASE=https://keycloak-stage.example.com \
#   KC_ADMIN_USER=admin \
#   KC_ADMIN_PASS=admin \
#   REALM=platform \
#   ./scripts/keycloak/provision-stage.sh

ENV_NAME="${ENV:-stage}"
KEYCLOAK_BASE="${KEYCLOAK_BASE:?Set KEYCLOAK_BASE (e.g. https://keycloak-stage.example.com)}"
KC_ADMIN_USER="${KC_ADMIN_USER:?Set KC_ADMIN_USER}"
KC_ADMIN_PASS="${KC_ADMIN_PASS:?Set KC_ADMIN_PASS}"
KC_ADMIN_REALM="${KC_ADMIN_REALM:-master}"
REALM="${REALM:-platform}"
REALM_IMPORT_FILE="${REALM_IMPORT_FILE:-}"

CLIENTS=( "gateway" "user-service" "permission-service" "variant-service" "frontend" )
SCOPES=( "aud-user-service:user-service" "aud-permission-service:permission-service" )

echo "[kc] base=${KEYCLOAK_BASE} realm=${REALM} env=${ENV_NAME}"

token() {
  curl -s -S -X POST "${KEYCLOAK_BASE}/realms/${KC_ADMIN_REALM}/protocol/openid-connect/token" \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    -d "grant_type=password&client_id=admin-cli&username=${KC_ADMIN_USER}&password=${KC_ADMIN_PASS}" | jq -r '.access_token'
}

ADMIN_TOKEN=$(token)
if [[ -z "$ADMIN_TOKEN" || "$ADMIN_TOKEN" == "null" ]]; then
  echo "[kc] Cannot get admin token" >&2
  exit 2
fi

kc_get() { curl -s -S -H "Authorization: Bearer ${ADMIN_TOKEN}" "$@"; }
kc_post() { curl -s -S -H "Authorization: Bearer ${ADMIN_TOKEN}" -H 'Content-Type: application/json' -X POST "$1" -d "$2"; }
kc_put() { curl -s -S -H "Authorization: Bearer ${ADMIN_TOKEN}" -H 'Content-Type: application/json' -X PUT "$1" -d "$2"; }

# Ensure realm exists
if ! kc_get "${KEYCLOAK_BASE}/admin/realms/${REALM}" | jq -e .realm >/dev/null 2>&1; then
  if [[ -n "${REALM_IMPORT_FILE}" && -f "${REALM_IMPORT_FILE}" ]]; then
    echo "[kc] importing realm ${REALM} from ${REALM_IMPORT_FILE}"
    kc_post "${KEYCLOAK_BASE}/admin/realms" "$(cat "${REALM_IMPORT_FILE}")" >/dev/null
  else
    echo "[kc] creating realm ${REALM}"
    kc_post "${KEYCLOAK_BASE}/admin/realms" "{\"realm\":\"${REALM}\",\"enabled\":true}" >/dev/null
  fi
else
  echo "[kc] realm ${REALM} exists"
fi

# Ensure audience client scopes
for entry in "${SCOPES[@]}"; do
  SCOPE_NAME="${entry%%:*}"
  AUD="${entry##*:}"
  if ! kc_get "${KEYCLOAK_BASE}/admin/realms/${REALM}/client-scopes" | jq -e ".[] | select(.name==\"${SCOPE_NAME}\")" >/dev/null; then
    echo "[kc] creating client scope ${SCOPE_NAME}"
    SCOPE_ID=$(kc_post "${KEYCLOAK_BASE}/admin/realms/${REALM}/client-scopes" "{\"name\":\"${SCOPE_NAME}\",\"protocol\":\"openid-connect\"}" -D - 2>/dev/null | awk '/Location:/ {print $2}' | sed 's#^.*/##;s#\r$##')
  else
    SCOPE_ID=$(kc_get "${KEYCLOAK_BASE}/admin/realms/${REALM}/client-scopes" | jq -r ".[] | select(.name==\"${SCOPE_NAME}\") | .id")
  fi
  # Add audience mapper
  echo "[kc] ensuring audience mapper for ${SCOPE_NAME} -> ${AUD}"
  kc_post "${KEYCLOAK_BASE}/admin/realms/${REALM}/client-scopes/${SCOPE_ID}/protocol-mappers/models" \
    "{\"name\":\"${SCOPE_NAME}\",\"protocol\":\"openid-connect\",\"protocolMapper\":\"oidc-audience-mapper\",\"config\":{\"included.client.audience\":\"${AUD}\",\"id.token.claim\":\"false\",\"access.token.claim\":\"true\"}}" >/dev/null || true
done

# Ensure clients and capture secrets
for cid in "${CLIENTS[@]}"; do
  echo "[kc] ensuring client ${cid}"
  if ! kc_get "${KEYCLOAK_BASE}/admin/realms/${REALM}/clients?clientId=${cid}" | jq -e '.[0].id' >/dev/null; then
    kc_post "${KEYCLOAK_BASE}/admin/realms/${REALM}/clients" \
      "{\"clientId\":\"${cid}\",\"protocol\":\"openid-connect\",\"serviceAccountsEnabled\":true,\"publicClient\":false,\"standardFlowEnabled\":false,\"directAccessGrantsEnabled\":false}" >/dev/null
  fi
  CID=$(kc_get "${KEYCLOAK_BASE}/admin/realms/${REALM}/clients?clientId=${cid}" | jq -r '.[0].id')
  # assign default client scopes
  for entry in "${SCOPES[@]}"; do
    SCOPE_NAME="${entry%%:*}"
    SCOPE_ID=$(kc_get "${KEYCLOAK_BASE}/admin/realms/${REALM}/client-scopes" | jq -r ".[] | select(.name==\"${SCOPE_NAME}\") | .id")
    kc_put "${KEYCLOAK_BASE}/admin/realms/${REALM}/clients/${CID}/default-client-scopes/${SCOPE_ID}" '{}' >/dev/null || true
  done
  # get or regenerate secret
  SECRET=$(kc_post "${KEYCLOAK_BASE}/admin/realms/${REALM}/clients/${CID}/client-secret" '{}' | jq -r .value)
  echo "[kc] ${cid} client-secret: ${SECRET:0:4}********"
  if [[ -n "${VAULT_ADDR:-}" && -n "${VAULT_TOKEN:-}" && "${cid}" == "permission-service" ]]; then
    echo "[vault] writing permission-service oauth secret to secret/${ENV_NAME}/permission-service/oauth"
    curl -s -S -H "X-Vault-Token: ${VAULT_TOKEN}" -H 'Content-Type: application/json' \
      -X POST "${VAULT_ADDR}/v1/secret/data/${ENV_NAME}/permission-service/oauth" \
      -d "{\"data\":{\"client-id\":\"permission-service\",\"client-secret\":\"${SECRET}\"}}" >/dev/null
  fi
done

# Ensure permissions claim mapper for frontend client
FRONTEND_ID=$(kc_get "${KEYCLOAK_BASE}/admin/realms/${REALM}/clients?clientId=frontend" | jq -r '.[0].id')
if [[ -n "${FRONTEND_ID}" && "${FRONTEND_ID}" != "null" ]]; then
  if ! kc_get "${KEYCLOAK_BASE}/admin/realms/${REALM}/clients/${FRONTEND_ID}/protocol-mappers/models" | jq -e '.[] | select(.name=="permissions-claim")' >/dev/null; then
    echo "[kc] creating permissions claim mapper on frontend client (source=user attribute 'permissions')"
    kc_post "${KEYCLOAK_BASE}/admin/realms/${REALM}/clients/${FRONTEND_ID}/protocol-mappers/models" \
      '{
        "name":"permissions-claim",
        "protocol":"openid-connect",
        "protocolMapper":"oidc-usermodel-attribute-mapper",
        "config":{
          "user.attribute":"permissions",
          "claim.name":"permissions",
          "jsonType.label":"String",
          "multivalued":"true",
          "id.token.claim":"true",
          "access.token.claim":"true",
          "userinfo.token.claim":"true"
        }
      }' >/dev/null
  else
    echo "[kc] permissions-claim mapper already exists on frontend client"
  fi
else
  echo "[kc] warning: frontend client not found, permissions claim mapper not created" >&2
fi

echo "[kc] done"

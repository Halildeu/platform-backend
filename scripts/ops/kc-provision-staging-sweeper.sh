#!/usr/bin/env bash
# kc-provision-staging-sweeper.sh — idempotent Keycloak client provisioner.
#
# 2026-04-19 QLTY-PROACTIVE-05: creates (or updates) a confidential client
# `staging-sweeper` in the `serban` realm with:
#   - directAccessGrantsEnabled=true (password grant allowed)
#   - audience mappers for variant-service, user-service, permission-service,
#     report-service, core-data-service, schema-service, api-gateway
#   - standard OIDC flow disabled (service-centric client)
#
# Rationale: `staging-console-crawler.mjs` needs a token whose `aud` claim
# covers every backend audience validator. `canary-load` does NOT have these
# mappers → every secured API returned 401 (QLTY-PROACTIVE-02 investigation).
# Rather than piggyback on `canary-load`, we provision a dedicated client so
# crawler tokens look like real frontend tokens for validation purposes.
#
# Usage (from stage box, or via SSH):
#   KC_URL=http://127.0.0.1:8081 \
#   KC_ADMIN_USER=admin \
#   KC_ADMIN_PASS=<fetched-from-docker-inspect> \
#   backend/scripts/ops/kc-provision-staging-sweeper.sh
#
# Output: prints the newly-minted client_secret for `staging-sweeper`.
# Store in GitHub secret `STAGING_SWEEPER_CLIENT_SECRET` (or compose env).
#
# Safe to re-run: if the client already exists, mappers are reconciled and
# a new secret is rotated. Backwards-compatible: existing tokens continue
# to work until expiry.

set -euo pipefail

KC_URL="${KC_URL:-http://127.0.0.1:8081}"
REALM="${REALM:-serban}"
KC_ADMIN_USER="${KC_ADMIN_USER:-admin}"
KC_ADMIN_PASS="${KC_ADMIN_PASS:-}"
CLIENT_ID="${CLIENT_ID:-staging-sweeper}"

AUDIENCES=(
  "variant-service"
  "user-service"
  "permission-service"
  "report-service"
  "core-data-service"
  "schema-service"
  "api-gateway"
)

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "[kc-prov] FATAL: '$1' required" >&2
    exit 2
  fi
}

require_cmd curl
require_cmd jq

if [[ -z "${KC_ADMIN_PASS}" ]]; then
  echo "[kc-prov] FATAL: KC_ADMIN_PASS env required (docker inspect platform-keycloak-1 --format '...')" >&2
  exit 2
fi

# ---------- Admin token (master realm) ----------
echo "[kc-prov] minting admin token (master realm, admin-cli)..."
ADMIN_TOKEN="$(
  curl -sS -X POST "${KC_URL}/realms/master/protocol/openid-connect/token" \
    -d "grant_type=password" \
    -d "client_id=admin-cli" \
    -d "username=${KC_ADMIN_USER}" \
    -d "password=${KC_ADMIN_PASS}" \
  | jq -r '.access_token // empty'
)"
if [[ -z "${ADMIN_TOKEN}" ]]; then
  echo "[kc-prov] FATAL: could not mint admin token; check KC_ADMIN_USER/PASS" >&2
  exit 2
fi

# ---------- Check if client exists ----------
echo "[kc-prov] checking if client ${CLIENT_ID} exists in realm ${REALM}..."
EXISTING="$(
  curl -sS -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    "${KC_URL}/admin/realms/${REALM}/clients?clientId=${CLIENT_ID}" \
  | jq -r '.[0].id // empty'
)"

if [[ -n "${EXISTING}" ]]; then
  echo "[kc-prov] client exists (uuid=${EXISTING}); will reconcile mappers + rotate secret"
  CLIENT_UUID="${EXISTING}"
else
  echo "[kc-prov] client does not exist; creating..."
  curl -sS -X POST -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H "Content-Type: application/json" \
    "${KC_URL}/admin/realms/${REALM}/clients" \
    -d '{
      "clientId": "'"${CLIENT_ID}"'",
      "protocol": "openid-connect",
      "enabled": true,
      "publicClient": false,
      "standardFlowEnabled": false,
      "implicitFlowEnabled": false,
      "directAccessGrantsEnabled": true,
      "serviceAccountsEnabled": false,
      "frontchannelLogout": false,
      "attributes": {
        "access.token.lifespan": "3600"
      }
    }' >/dev/null

  CLIENT_UUID="$(
    curl -sS -H "Authorization: Bearer ${ADMIN_TOKEN}" \
      "${KC_URL}/admin/realms/${REALM}/clients?clientId=${CLIENT_ID}" \
    | jq -r '.[0].id // empty'
  )"
  if [[ -z "${CLIENT_UUID}" ]]; then
    echo "[kc-prov] FATAL: could not resolve client uuid after create" >&2
    exit 2
  fi
  echo "[kc-prov] client created uuid=${CLIENT_UUID}"
fi

# ---------- Reconcile audience mappers ----------
echo "[kc-prov] reconciling audience mappers (${#AUDIENCES[@]} total)..."
# Fetch existing mappers
EXISTING_MAPPERS="$(
  curl -sS -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    "${KC_URL}/admin/realms/${REALM}/clients/${CLIENT_UUID}/protocol-mappers/models"
)"

for aud in "${AUDIENCES[@]}"; do
  mapper_name="aud-${aud}"
  existing_id="$(echo "${EXISTING_MAPPERS}" | jq -r --arg name "${mapper_name}" '.[] | select(.name == $name) | .id // empty' | head -1)"
  if [[ -n "${existing_id}" ]]; then
    echo "[kc-prov]   mapper '${mapper_name}' already present (id=${existing_id}), skipping"
    continue
  fi
  curl -sS -X POST -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H "Content-Type: application/json" \
    "${KC_URL}/admin/realms/${REALM}/clients/${CLIENT_UUID}/protocol-mappers/models" \
    -d '{
      "name": "'"${mapper_name}"'",
      "protocol": "openid-connect",
      "protocolMapper": "oidc-audience-mapper",
      "consentRequired": false,
      "config": {
        "included.custom.audience": "'"${aud}"'",
        "id.token.claim": "false",
        "access.token.claim": "true"
      }
    }' >/dev/null
  echo "[kc-prov]   mapper '${mapper_name}' created"
done

# ---------- Rotate client secret ----------
echo "[kc-prov] rotating client secret..."
NEW_SECRET="$(
  curl -sS -X POST -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    "${KC_URL}/admin/realms/${REALM}/clients/${CLIENT_UUID}/client-secret" \
  | jq -r '.value // empty'
)"
if [[ -z "${NEW_SECRET}" ]]; then
  echo "[kc-prov] FATAL: could not rotate client secret" >&2
  exit 2
fi

echo "[kc-prov] ------------------------------------------------------------"
echo "[kc-prov] client_id=${CLIENT_ID}"
echo "[kc-prov] client_uuid=${CLIENT_UUID}"
echo "[kc-prov] client_secret=${NEW_SECRET}"
echo "[kc-prov] ------------------------------------------------------------"
echo "[kc-prov] Store this secret in:"
echo "[kc-prov]   - GitHub secret STAGING_SWEEPER_CLIENT_SECRET"
echo "[kc-prov]   - canonical env (/home/halil/platform/env/backend.env):"
echo "[kc-prov]       STAGING_SWEEPER_CLIENT_SECRET=${NEW_SECRET}"
echo "[kc-prov]"
echo "[kc-prov] Then run the crawler with:"
echo "[kc-prov]   TOKEN_STRATEGY=password-grant \\"
echo "[kc-prov]     KC_CLIENT_ID=${CLIENT_ID} \\"
echo "[kc-prov]     KC_CLIENT_SECRET=${NEW_SECRET} \\"
echo "[kc-prov]     node web/scripts/ops/staging-console-crawler.mjs"

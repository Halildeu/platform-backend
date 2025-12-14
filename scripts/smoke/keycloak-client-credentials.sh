#!/usr/bin/env bash
set -euo pipefail

# Smoke test: obtain client_credentials token from Keycloak and print claims.
#
# Usage:
#   KEYCLOAK_TOKEN_URL=https://keycloak-stage.example.com/realms/platform/protocol/openid-connect/token \
#   CLIENT_ID=permission-service \
#   CLIENT_SECRET=... \
#   ./scripts/smoke/keycloak-client-credentials.sh

TOKEN_URL="${KEYCLOAK_TOKEN_URL:?Set KEYCLOAK_TOKEN_URL}"
CLIENT_ID="${CLIENT_ID:?Set CLIENT_ID}"
CLIENT_SECRET="${CLIENT_SECRET:?Set CLIENT_SECRET}"

echo "[smoke] requesting token for client_id=${CLIENT_ID}"
ACCESS_TOKEN=$(curl -s -S -X POST "${TOKEN_URL}" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d "grant_type=client_credentials&client_id=${CLIENT_ID}&client_secret=${CLIENT_SECRET}" | jq -r '.access_token // empty')

if [[ -z "$ACCESS_TOKEN" ]]; then
  echo "[smoke] failed to get token" >&2
  exit 2
fi

echo "[smoke] token length: ${#ACCESS_TOKEN}"

decode() {
  python3 - <<'PY'
import os,sys,base64,json
tok=os.environ.get('TOK','')
parts=tok.split('.')
for i,p in enumerate(parts[:2]):
  pad='='*((4-len(p)%4)%4)
  data=base64.urlsafe_b64decode(p+pad)
  print(['header','payload'][i]+':')
  print(json.dumps(json.loads(data.decode()), indent=2))
PY
}

TOK="$ACCESS_TOKEN" decode

echo "[smoke] ok"


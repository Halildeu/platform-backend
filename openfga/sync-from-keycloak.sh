#!/usr/bin/env bash
# Sync Keycloak role assignments to OpenFGA tuples.
# Uses Keycloak Admin REST API to read role/user mappings,
# then writes corresponding tuples to OpenFGA.
#
# Usage: ./sync-from-keycloak.sh [KEYCLOAK_URL] [OPENFGA_URL] [STORE_ID]
#
# Prerequisites: curl, jq, python3
set -euo pipefail

KEYCLOAK_URL="${1:-http://localhost:8081}"
OPENFGA_URL="${2:-http://localhost:4000}"
STORE_ID="${3:-${OPENFGA_STORE_ID:-}}"
REALM="serban"
KC_ADMIN="admin"
KC_PASS="admin"

if [ -z "$STORE_ID" ]; then
  echo "ERROR: STORE_ID required. Pass as arg or set OPENFGA_STORE_ID env var."
  exit 1
fi

echo "=== Keycloak → OpenFGA Sync ==="
echo "Keycloak: $KEYCLOAK_URL (realm: $REALM)"
echo "OpenFGA:  $OPENFGA_URL (store: $STORE_ID)"

# 1. Get Keycloak admin token
echo ""
echo "Getting Keycloak admin token..."
KC_TOKEN=$(curl -sf -X POST "$KEYCLOAK_URL/realms/master/protocol/openid-connect/token" \
  -d "client_id=admin-cli" \
  -d "username=$KC_ADMIN" \
  -d "password=$KC_PASS" \
  -d "grant_type=password" | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

if [ -z "$KC_TOKEN" ]; then
  echo "ERROR: Failed to get Keycloak token"
  exit 1
fi
echo "Token acquired."

# 2. Get all users
echo ""
echo "Fetching users..."
USERS=$(curl -sf "$KEYCLOAK_URL/admin/realms/$REALM/users?max=1000" \
  -H "Authorization: Bearer $KC_TOKEN")

USER_COUNT=$(echo "$USERS" | python3 -c "import sys,json; print(len(json.load(sys.stdin)))")
echo "Found $USER_COUNT users."

# 3. For each user, get realm role mappings and write tuples
echo ""
echo "Syncing role assignments..."

TUPLES_WRITTEN=0
TUPLES_SKIPPED=0

echo "$USERS" | python3 -c "
import sys, json, subprocess

users = json.load(sys.stdin)
keycloak_url = '$KEYCLOAK_URL'
realm = '$REALM'
token = '$KC_TOKEN'
openfga_url = '$OPENFGA_URL'
store_id = '$STORE_ID'

# Role name → OpenFGA relation mapping
ROLE_MAP = {
    'admin': 'admin',
    'viewer': 'viewer',
    'manager': 'manager',
    'editor': 'editor',
    'operator': 'operator',
}

tuples_to_write = []

for user in users:
    user_id = user['id']
    username = user.get('username', 'unknown')

    # Get realm role mappings
    import urllib.request
    req = urllib.request.Request(
        f'{keycloak_url}/admin/realms/{realm}/users/{user_id}/role-mappings/realm',
        headers={'Authorization': f'Bearer {token}'}
    )
    try:
        with urllib.request.urlopen(req) as resp:
            roles = json.loads(resp.read())
    except Exception as e:
        print(f'  WARN: Could not fetch roles for {username}: {e}')
        continue

    for role in roles:
        role_name = role['name'].lower()
        if role_name in ROLE_MAP:
            relation = ROLE_MAP[role_name]
            # For now, sync as global role (no company scope from Keycloak)
            # Company-specific assignments will be managed directly via OpenFGA
            tuple_key = {
                'user': f'user:{user_id}',
                'relation': relation,
                'object': f'organization:default'
            }
            tuples_to_write.append(tuple_key)
            print(f'  {username} → {relation} on organization:default')

if tuples_to_write:
    # Write all tuples in one batch
    write_body = json.dumps({'writes': {'tuple_keys': tuples_to_write}})
    req = urllib.request.Request(
        f'{openfga_url}/stores/{store_id}/write',
        data=write_body.encode(),
        headers={'Content-Type': 'application/json'},
        method='POST'
    )
    try:
        with urllib.request.urlopen(req) as resp:
            print(f'\\nWritten {len(tuples_to_write)} tuples to OpenFGA.')
    except urllib.error.HTTPError as e:
        body = e.read().decode()
        if 'cannot write a tuple' in body.lower() and 'already exists' in body.lower():
            print(f'\\nTuples already exist (idempotent). {len(tuples_to_write)} checked.')
        else:
            print(f'\\nERROR writing tuples: {e.code} {body}')
else:
    print('\\nNo tuples to write.')
"

echo ""
echo "=== Sync Complete ==="
echo ""
echo "NOTE: This script syncs realm-level roles only."
echo "Company/project/warehouse-specific assignments should be"
echo "managed directly via OpenFGA API or the admin UI."
echo ""
echo "Run periodically or after bulk Keycloak changes."
echo "For real-time sync, Phase 3 will add event-driven integration."

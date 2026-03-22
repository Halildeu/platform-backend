#!/bin/bash
# Enable WebAuthn/Passkeys for Keycloak serban realm
KC_URL=${KC_URL:-http://localhost:8081}
REALM=serban

# Get admin token
TOKEN=$(curl -s -X POST "$KC_URL/realms/master/protocol/openid-connect/token" \
  -d "grant_type=password&client_id=admin-cli&username=admin&password=admin" | \
  python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

# Enable WebAuthn authenticator
curl -s -X PUT "$KC_URL/admin/realms/$REALM" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "webAuthnPolicyRpEntityName": "Serban Platform",
    "webAuthnPolicySignatureAlgorithms": ["ES256"],
    "webAuthnPolicyRpId": "",
    "webAuthnPolicyAttestationConveyancePreference": "not specified",
    "webAuthnPolicyAuthenticatorAttachment": "not specified",
    "webAuthnPolicyRequireResidentKey": "not specified",
    "webAuthnPolicyUserVerificationRequirement": "not specified"
  }'

echo "WebAuthn/Passkeys enabled for realm: $REALM"

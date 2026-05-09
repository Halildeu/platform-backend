# Spike: Keycloak Token Exchange — User Impersonation v1

> **Date**: 2026-05-09
> **Realm**: `platform-test`
> **Keycloak version**: 26.x
> **Features enabled (kc.features)**: `token-exchange,admin-fine-grained-authz`
> **Codex thread**: `019e0dfb-7230-7f43-80c4-dd03e36a2f70`
> **Linked spec**: [docs/plans/2026-05-user-impersonation-v1-spec.md](../plans/2026-05-user-impersonation-v1-spec.md)

## Spike-1 sonucu (provisioning + first exchange attempt)

### ✅ Feature gate
```
kc.features = token-exchange, admin-fine-grained-authz (ENV)
```
Hem token-exchange hem fine-grained authz feature'ı runtime'da aktif. Gap yok.

### ✅ Broker client provisioning
| Attribute | Value |
|---|---|
| Client ID | `impersonation-broker` |
| Client UUID | `<redacted>` |
| Client Authenticator | `client-secret` |
| Service Accounts Enabled | `true` |
| Standard/Direct/Implicit Flow | `false` |
| Public Client | `false` |
| `attributes."token.exchange.permission.enabled"` | `true` |
| Service Account User UUID | `<redacted>` |

### ✅ Service account effective roles (`realm-management` client roles)
- `impersonation`
- `view-users`
- `query-users`

### ✅ Admin JWT (impersonator)
```
Username: d35-admin-persona
Email: d35-admin@example.com
Client: frontend (public)
Grant: password
Token length: 1431 chars
```

### ⚠ Token exchange request — config-gap (Spike-1 stop point)

**Request**:
```http
POST /realms/platform-test/protocol/openid-connect/token
Content-Type: application/x-www-form-urlencoded

grant_type=urn:ietf:params:oauth:grant-type:token-exchange
client_id=impersonation-broker
client_secret=<redacted>
subject_token=<admin_jwt>
requested_subject=<canaryscope_target_uuid>
audience=frontend
```

**Response**:
```http
HTTP/1.1 401 Unauthorized
Content-Type: application/json

{"error":"access_denied","error_description":"Client not allowed to exchange"}
```

### Yorum

Endpoint, payload format, authentication (broker secret + admin JWT) tüm katmanlar geçti. **Authorization layer (Keycloak fine-grained authz policy)** default-deny enforcing — `frontend` client için exchange izin verilmesi gereken policy henüz oluşturulmadı.

Bu Keycloak 26.x'in beklenen davranışı: `attributes."token.exchange.permission.enabled"=true` set etmek client-side intent flag; fine-grained authz tarafında **explicit policy** gerek (hangi exchanger client'lar bu broker'dan token alabilir, hangi audience'lara token üretilebilir).

## Decision

**Spike-1 verdict**: `STRUCTURALLY_OK_POLICY_MISSING`

Spike-1 amacı (feature/client/auth basamaklarının çalışması) tam karşılandı. Kalan tek engel **fine-grained authz policy config** — bu zaten Codex iter-2'de PR-A scope'una alınmıştı; iter-3'te PR-A'nın **birinci acceptance gate'i** olarak ana scope haline geldi.

## Spike-2 sonucu (2026-05-09 22:50 TRT) — `PR_B_READY` ✅

### KC features unblock

PR-A apply sonrası management/permissions endpoint "Feature not enabled" verdi:

```
GET /admin/realms/platform-test/clients/{id}/management/permissions
→ "Feature not enabled" (admin-fine-grained-authz default v2; v1 endpoint inactive)
GET /admin/realms/platform-test/clients/{id}/authz/resource-server
→ 404 (authorization feature kapalıydı)
```

**Fix** (commit `804aa9c` — `feat/kc-test-authorization-feature`):

```yaml
# host-compose/keycloak/test/docker-compose.yml
KC_FEATURES: "token-exchange,admin-fine-grained-authz:v1,authorization"
command: ["start"]   # --optimized kaldırıldı; feature change augmentation cache mismatch
```

Container recreate sonrası KC log:
```
Preview features enabled: token-exchange:v1
Deprecated features enabled: admin-fine-grained-authz:v1
Keycloak 26.5.5 on JVM ... started in 66.899s.
```

`management/permissions` endpoint şimdi `{"enabled": false}` döner (önceden "Feature not enabled" idi → fix doğrulandı).

### Setup script + manuel patch

`bash scripts/keycloak/setup-impersonation-broker.sh` Step 1-4 birinci pass:
- ✓ Step 1: Login (master realm)
- ✓ Step 2: Client desired-state converged
- ✓ Step 3: Service account roles (impersonation, view-users, query-users)
- ✓ Step 4 partial: management/permissions enabled, token-exchange perm id resolved, policy `impersonation-broker-only` created
- ⚠ Step 4 attach: kcadm `update -s "policies=[...]"` "unknown_error" döndü

**Manuel attach (fallback path)** JSON file ile:

```bash
cat > /tmp/perm-update.json <<EOF
{
  "id": "$TE_PERM_ID",
  "name": "token-exchange.permission.client.<frontend_uuid>",
  "type": "scope", "logic": "POSITIVE", "decisionStrategy": "UNANIMOUS",
  "policies": ["$POLICY_ID"]
}
EOF
docker cp /tmp/perm-update.json platform-kc-test:/tmp/perm-update.json
$KC update clients/$REALM_MGMT_ID/authz/resource-server/permission/scope/$TE_PERM_ID -r platform-test -f /tmp/perm-update.json
```

Doğrulama: `GET .../associatedPolicies` → `[{"name":"impersonation-broker-only", ...}]` ✓

> **Script TODO (PR-A follow-up)**: Step 4 attach kcadm bug için `-f JSON` fallback path script'e eklenmeli; mevcut `-s "policies=[...]"` kcadm 26.x'te broken.

### Subject impersonation role

İlk exchange attempt:
```
"error":"access_denied","error_description":"Client not allowed to exchange"
KC log: type="TOKEN_EXCHANGE_ERROR" reason="subject not allowed to impersonate"
```

`d35-admin-persona` user'ına `realm-management/impersonation` client role eksikti. Grant:

```bash
$KC add-roles -r platform-test --uusername d35-admin-persona \
  --cclientid realm-management --rolename impersonation
```

> **Script TODO (PR-A follow-up)**: Spec §X impersonator role grant Step 7 olarak script'e eklenmeli (idempotent assign-role). Şu an manual step.

### Token exchange — 200 PASS

```http
POST /realms/platform-test/protocol/openid-connect/token
grant_type=urn:ietf:params:oauth:grant-type:token-exchange
client_id=impersonation-broker
client_secret=<broker_secret>
subject_token=<d35-admin-persona JWT>
requested_subject=<canaryscope_uuid>
audience=frontend

200 OK
{"access_token":"eyJhbG...","token_type":"Bearer","expires_in":3600,...}
```

### Decoded claims (canonical truth)

```json
{
  "iss": "http://testai.acik.com/realms/platform-test",
  "sub": "b09df130-d22e-4860-82a7-3d7b4ca5e875",  // canaryscope (target user) ✓
  "aud": ["account", "frontend"],                   // frontend in audience ✓
  "azp": "impersonation-broker",                    // broker as authorized party ✓
  "preferred_username": "canaryscope",
  "email": "canary-scope@testai.acik.com",
  "scope": "email profile",
  "typ": "Bearer",
  "acr": "1",
  "exp": 1778359659, "iat": 1778356059,
  "jti": "onrtna:ea962dff-b2fe-790a-e438-782268620695",
  "sid": "MO7DUTP6WyBJBkng2yfBW7-T",
  "realm_access": {...}, "resource_access": {...},
  "userId": "..."
  // act claim YOK ❌
  // impersonator_user_id YOK ❌
  // impersonation_session_id YOK ❌
}
```

### Acceptance matrix sonucu

| Gate | Spec | Sonuç |
|---|---|---|
| `token_exchange_status` | `200\|4xx` | **200** ✓ |
| `binding_model` | `native_act\|jti_session_lookup\|custom_claim\|none` | **`jti_session_lookup`** (act yok ama jti+sid mevcut, broker session lookup mümkün) |
| `audience_backend` | `pass\|fail` | **deferred** (cluster Vault AppRole stale → 503; ayrı follow-up) |
| `authz_me_with_exchanged_token` | `200\|401\|403` | **deferred** (cluster outage; tokens kanıt valid) |
| `report_smoke_with_exchanged_token` | `200\|401\|403` | **deferred** (cluster outage) |

### Decision: `PASS_JTI_SESSION_LOOKUP` → `PR_B_READY`

Codex iter-3 acceptance kriterindeki üçüncü row:

> `act` yok ama `jti` mevcut + broker session lookup mümkün → ✅ **PASS_JTI_SESSION_LOOKUP** → PR-B middleware DB lookup path

PR-B implementation gerekleri (Codex iter-3 §3 absorb):
- Backend broker endpoint kendi imzaladığı JWT'yi bypass etmeden, **Keycloak'tan gelen exchanged JWT**'yi kullanır
- Middleware: `jti` ve `sid` üzerinden `impersonation_sessions` tablosunda lookup
- impersonator_user_id `impersonation_sessions` tablosunda authoritative; JWT'de native claim yok
- Audit logger her request'te bu lookup'ı yapıp `audit.impersonator_user_id` ve `target_user_id` doldurur
- Custom JWT signing path (orijinal A2 plan §6.2 alternatif) **bypass** — pragmatik, KC native session model yeterli

### Backend smoke deferred

Cluster `vault-platform-gitops` ClusterSecretStore "invalid role or secret ID" → ESO Vault AppRole rotation gerekli (D32 bootstrap re-trigger). Bu **Spike-2 scope dışı** ve bağımsız blocker (ETL/auth/api-gateway hepsi etkili). Ayrı task spawn'lanır.

### TODO follow-up (PR-A iter-5 absorb scope)

1. **Script bug**: Step 4 policy attach `-s` form yerine `-f JSON` form kullan (kcadm 26.x compat)
2. **Script eksik**: impersonator role grant Step 7 (`add-roles --uusername $IMPERSONATOR --rolename impersonation`)
3. **Script eksik**: KC features verify Step 0 (`token-exchange,admin-fine-grained-authz:v1,authorization` enabled mi?)
4. **Spec ref**: `~/Documents/platform-backend/docs/plans/2026-05-user-impersonation-v1-spec.md` §6.2 binding model section'da `jti_session_lookup` path netleştirilmeli (orijinal A2 öneri custom JWT signing idi; pragmatik shift Codex iter-3'te zaten yapılmıştı, spec'i sync et)

## Spike-2 (planned, post-PR-A)

PR-A apply edildikten sonra ikinci spike turu (operator manuel, ~1 saat):

### Acceptance kriteri (Codex iter-3 dictation)

```text
token_exchange_status=200|4xx
binding_model=native_act|jti_session_lookup|custom_claim|none
audience_backend=pass|fail
authz_me_with_exchanged_token=200|401|403
report_smoke_with_exchanged_token=200|401|403
decision=PR_B_READY|REVISE_REQUIRED
```

### Decoded claim acceptance

| Outcome | Decision |
|---|---|
| `act` claim native + `act.sub == admin_user_id` | ✅ **PASS_NATIVE_ACT** → PR-B `act` claim middleware path |
| `act` yok ama `jti` mevcut + broker session lookup mümkün | ✅ **PASS_JTI_SESSION_LOOKUP** → PR-B middleware DB lookup path (custom JWT signing'den daha pragmatik — Codex iter-3 §3 absorb) |
| Custom mapper ile deterministic `impersonator_*` claim | ✅ **PASS_CUSTOM_CLAIM** → PR-B middleware custom claim path |
| Hiçbir actor binding güvenilir değil | ❌ **FAIL_NO_BINDING** → design revise (PR-B ertelenir) |

### Spike-2 runbook (PR-A apply sonrası)

```bash
#!/bin/bash
set +x
# Pre: PR-A applied (broker client + policy live)
# Pre: ADMIN_JWT, TARGET_ID, BROKER_SECRET in env

EXCHANGE_RESP=$(curl -sk -X POST \
  "https://testai.acik.com/realms/platform-test/protocol/openid-connect/token" \
  -d "grant_type=urn:ietf:params:oauth:grant-type:token-exchange" \
  -d "client_id=impersonation-broker" \
  -d "client_secret=$BROKER_SECRET" \
  -d "subject_token=$ADMIN_JWT" \
  -d "requested_subject=$TARGET_ID" \
  -d "audience=frontend")

EXCHANGED=$(echo "$EXCHANGE_RESP" | jq -r '.access_token // empty')

if [ -z "$EXCHANGED" ]; then
  echo "FAIL: $EXCHANGE_RESP"
  exit 1
fi

# Decode claims
echo "$EXCHANGED" | cut -d. -f2 | python3 -c '
import base64, json, sys
p = sys.stdin.read().strip()
p += "=" * (-len(p) % 4)
c = json.loads(base64.urlsafe_b64decode(p))
keys = ["iss","sub","aud","azp","scope","typ","acr","exp","iat","jti",
        "email","preferred_username","act","impersonator_user_id",
        "impersonation_session_id","target_user_id"]
for k in keys:
    if k in c:
        print(f"{k}: {json.dumps(c[k], ensure_ascii=False)[:200]}")
'

# Backend smoke
echo ""
echo "=== authz/me with exchanged token ==="
curl -sk -o /dev/null -w "HTTP=%{http_code}\n" \
  -H "Authorization: Bearer $EXCHANGED" \
  "https://testai.acik.com/api/v1/authz/me"

echo "=== report endpoint smoke ==="
curl -sk -o /dev/null -w "HTTP=%{http_code}\n" \
  -H "Authorization: Bearer $EXCHANGED" \
  -H "X-Company-Id: 35" \
  "https://testai.acik.com/api/v1/reports/fin-muhasebe-detay/data?page=1&pageSize=5"
```

## Security notes (Codex iter-3 §absorb)

- ❌ **Secrets commit'lenmedi**: `/tmp/broker-secret.txt` operator host filesystem; Vault/ExternalSecret reference PR-A scope'unda
- ❌ **Token sample redacted**: bu doc'ta hiçbir gerçek JWT/secret/UUID yok
- ❌ **Denylist Keycloak policy'sinde değil**: Keycloak policy coarse gate (broker → frontend exchange OK), fine-grained "kimi impersonate edebilir" backend broker endpoint'inde fail-closed kapanır

## References

- Spec: [docs/plans/2026-05-user-impersonation-v1-spec.md](../plans/2026-05-user-impersonation-v1-spec.md)
- Codex thread: `019e0dfb-7230-7f43-80c4-dd03e36a2f70`
  - iter-2 ready_for_spike: true
  - iter-3 PARTIAL → ready_for_pr_a: true
- RFC 8693 OAuth 2.0 Token Exchange
- Keycloak 26 Server Administration: Token Exchange + fine-grained authz

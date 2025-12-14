# PR: RS256/JWKS Tutarlılığı + Mint by Auth + Gateway JWT & Export Guardrails

## Özet
- Tek anahtar kaynağı: Private key yalnız `auth-service`’de. Doğrulayıcılar JWKS ile.
- Servis→servis kimlik doğrulama: `user-service` artık servis token’ını `auth-service` üzerinden `client_credentials` ile mint ediyor.
- API Gateway: JWT doğrulama eklendi; export rotaları için hafif rate‑limit + `X-PII-Policy: mask` guardrails aktif.
- `variant-service` RS256/JWKS’e geçirildi.

## Kapsam
- Auth: `/oauth2/token` (client_credentials), allowlist + rate‑limit
- User: ServiceTokenProvider → HTTP mint (TTL cache, fail‑fast)
- Permission: issuer default netleşti (auth-service)
- Variant: Resource Server (JWKS), audience validator, principal mapping
- Gateway: JWT doğrulama, ExportGuardFilter (rate‑limit, PII header)

## Konfig / ENV
- auth-service
  - `security.service-clients.user-service=${SERVICE_CLIENT_USER_SERVICE_SECRET:dev-secret}`
  - `security.service-mint.allowed-audiences=permission-service`
  - `security.service-mint.allowed-permissions=permissions:read,permissions:write`
  - `security.service-mint.rate-limit-per-minute=120`
- user-service
  - `security.service-token.client.token-url=http://localhost:8088/oauth2/token`
  - `security.service-token.client.client-id=user-service`
  - `security.service-token.client.client-secret=dev-secret`
  - `security.service-token.audience=permission-service`
- permission-service
  - `security.jwt.jwk-set-uri=http://localhost:8088/oauth2/jwks`
  - `security.jwt.issuer=auth-service`
  - `security.jwt.audience=permission-service`
- variant-service
  - `SECURITY_JWT_JWK_SET_URI=http://localhost:8088/oauth2/jwks`
  - `SECURITY_JWT_ISSUER=auth-service`
  - `SECURITY_JWT_AUDIENCE=variant-service`
- api-gateway
  - `SECURITY_JWT_JWK_SET_URI=http://localhost:8088/oauth2/jwks`
  - `SECURITY_JWT_ISSUER=auth-service`
  - `export.rate-limit.per-minute=12`, `export.rate-limit.burst=24`

## Kabul Kriterleri
- Mint: `/oauth2/token` → 200; `invalid_client` (401), `invalid_audience`/`invalid_permission` (400), `rate_limited` (429)
- User list: `/api/users/all` → 200 (Authorization header ile)
- Variant: `/api/variants` → 200 (RS256 token)
- Gateway: JWT yok/invalid → 401; (opsiyonel) audience mismatch → 403; export rate‑limit → 429, `X-PII-Policy: mask` header var

## Smoke Komutları
```bash
# Mint
curl -X POST http://localhost:8088/oauth2/token \
  -H "Authorization: Basic dXNlci1zZXJ2aWNlOmRldi1zZWNyZXQ=" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials&audience=permission-service&permissions=permissions:read"

# Kullanıcı liste (gateway üzerinden)
export USER_JWT='<jwt>'
curl -i -H "Authorization: Bearer $USER_JWT" "http://localhost:8080/api/users/all?page=1&pageSize=25"

# Export limit testi
curl -i -H "Authorization: Bearer $USER_JWT" "http://localhost:8080/api/users/export.csv"
```

## Rollout / Rollback
- Rotasyon ve rollout adımları: `docs/security/service-jwt-keys.md` → “Prod Runbook: Hızlı Uygulama”
- Rollback: JWKS’te eski `kid`’i `active` yap, imzayı eski `kid` ile sürdür; izleme normalleşmeli.

## Doküman Linkleri
- `docs/security/client-credentials-jwt.md`
- `docs/security/service-jwt-keys.md` (Prod Runbook)
- `docs/architecture/backend-architecture.md`
- `api-gateway/README.md`


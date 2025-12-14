# API Gateway — JWT + Guardrails (Smoke)

Bu modül, Spring Cloud Gateway üzerinde JWT doğrulaması ve basit export guardrails’i uygular.

## JWT Doğrulama
- JWKS: `SECURITY_JWT_JWK_SET_URI` (default: `http://localhost:8088/oauth2/jwks`)
- Issuer: `SECURITY_JWT_ISSUER` (default: `auth-service`)
- Audience: `SECURITY_JWT_AUDIENCE` (opsiyonel; route bazlı kontrol ileride eklenebilir)

Kod: `src/main/java/com/example/apigateway/security/SecurityConfig.java`

## Export Guard (Rate‑limit + PII Policy Header)
- Global filter: `ExportGuardFilter`
- Path’ler: `/api/users/export.csv`, `/api/audit/events/export`
- Konfig:
  - `export.rate-limit.per-minute` (default: 12)
  - `export.rate-limit.burst` (default: 24)
- Aşım: `429 Too Many Requests`
- Downstream isteğine `X-PII-Policy: mask` header’ı eklenir.

Kod: `src/main/java/com/example/apigateway/security/ExportGuardFilter.java`

## Smoke Testleri
- `ExportGuardFilterTest` basit davranışları doğrular:
  - İlk istek izinli, eşik aşılınca 429
  - Export isteklerinde `X-PII-Policy: mask` header’ı enjekte edilir
  - Export olmayan path’ler filtre tarafından engellenmez

Çalıştırma:

```bash
./mvnw -f api-gateway/pom.xml test
```

## Çalıştırma (lokal)
- Gerekli env (opsiyonel, defaults mevcut):
  - `SECURITY_JWT_JWK_SET_URI=http://localhost:8088/oauth2/jwks`
  - `SECURITY_JWT_ISSUER=auth-service`
- Uygulama:

```bash
./mvnw -f api-gateway/pom.xml spring-boot:run
```

Ardından FE isteklerini `http://localhost:8080` üzerinden iletin.


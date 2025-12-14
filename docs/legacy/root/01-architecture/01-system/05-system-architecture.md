---
title: "Sistem Mimarisi (Context/Container/Component)"
status: in-progress
owner: "@team/platform-arch"
last_review: 2025-11-08
tags: ["architecture", "c4", "overview"]
---

# Sistem Mimarisi

Bu doküman sistemin üst seviye bağlamını, konteynerlerini ve ana bileşenlerini özetler. Teknik kanıt ve test referansları ilgili dosya yollarıyla verilmiştir.

## 1) Context

- Kullanıcılar (Admin/Operatör) → SPA Shell (Keycloak-js) + MFEs → API Gateway → Discovery → Backend servisler → Postgres/Vault.
- Kimlik doğrulama Keycloak (`serban` realm) üzerinden yapılır; gateway ve tüm servisler RS256/JWKS resource server olarak çalışır. Prod/test profillerinde yalnız Keycloak JWT kabul edilir, local/dev’de permitAll + mock profiller vardır.
- FE çağrılarının tamamı `packages/shared-http` üzerindeki ortak axios instance’ı ile `http://localhost:8080/api/v1/**` path’lerine gider; traceId + Authorization header’ı otomatik eklenir.

## 2) Container (Servisler/MFE’ler)

- api-gateway (Spring Cloud Gateway)
  - Rotalar: `/api/v1/users|roles|permissions|variants|auth/**` (discovery üzerinden lb:// servislerine bağlanır)
  - Güvenlik: RS256/JWKS doğrulama (`issuer=http://localhost:8081/realms/serban`); dev/local profillerde `spring.security.oauth2.resourceserver.jwt.enabled=false` + permitAll. Global CORS whitelist’i `http://localhost:3000` origin’ine `authorization,content-type,x-trace-id` header’larıyla izin verir.
  - Konfig: api-gateway/src/main/resources/application*.yml (prod/test Vault import, local/dev jwt.enabled=false)
- auth-service
  - Kullanıcı kayıt/şifre reset/kurum içi login akışları. Legacy service-token uçları yalnızca local/dev profillerde tutulur; prod/test Keycloak’a delege edilir.
  - Kod: auth-service/src/main/java/com/example/auth/security/SecurityConfig.java:1
- user-service
  - Listeleme `/api/v1/users`, export `/api/v1/users/export.csv`, update `/api/v1/users/{id}`
  - AdvancedFilter whitelist + tip güvenliği; çoklu ORDER BY; CSV streaming
  - Güvenlik: Keycloak `aud=frontend,user-service` doğrulaması; legacy filtreler sadece local/dev profillerde
  - Kod: user-service/src/main/java/com/example/user/controller/UserController.java:1, user-service/src/main/java/com/example/user/security/SecurityConfig.java:1
- permission-service
  - `/api/v1/roles`, `/api/v1/permissions`, audit feed; yalnızca Keycloak JWT kabul eder (internal API key filtresi local/dev ile sınırlı)
- variant-service
  - Grid varyantları uçları `/api/v1/variants`; RS256/JWKS doğrulama; UI Kit grid helper’larıyla hizalı DTO
  - Kod: variant-service/src/main/java/com/example/variant/security/SecurityConfig.java:1
- Frontend Shell + MFEs
  - SSRM grid, advancedFilter → URL-encoded JSON; UI‑Kit `advancedFilter` yardımcıları; ortak HTTP client (`@mfe/shared-http/api`), Keycloak SPA login + silent-check-sso
  - Kod: packages/ui-kit/src/lib/filters/advancedFilter.ts:1, packages/ui-kit/src/lib/grid/README.md:1

## 3) Component (Akışlar)

- Login: FE → Keycloak (Authorization Code + PKCE); access token FE Shell’de saklanır → API çağrılarında shared-http interceptor `Authorization: Bearer` header’ını otomatik ekler. Logout → Keycloak session terminate.
- Kullanıcı Listeleme: MFE Users → gateway `/api/v1/users` → user-service JPA sorgu (sort/advancedFilter/search) → JSON
- Export CSV: MFE Users → gateway `/api/v1/users/export.csv` (guard header + rate-limit) → user-service streaming → indir
- Audit: Mutasyon sonrası `auditId` üretimi; FE toast + `/admin/audit?event=<auditId>` deep-link

## 4) Veri Modeli ve Depolama

- User tablosu (index: `lower(email)`, `create_date`) → Flyway migration:
  - user-service/src/main/resources/db/migration/V1__create_users_table.sql:1
  - user-service/src/main/resources/db/migration/V2__ensure_users_table.sql:1

## 5) Dayanıklılık ve Ölçek

- Yatay ölçek: gateway ve servisler stateless
- Rate-limit: export istekleri gateway tarafında hafif token bucket
- Bağlantı havuzu: HikariCP (varsayılan)
- Timeouts/Retries: (Gerekirse) gateway filtresi veya WebClient düzeyinde ADR ile tanımlanacak

## 6) Gözlemlenebilirlik

- Prometheus endpoint’leri: `/actuator/prometheus`
  - user-service/src/main/resources/application.properties:1
  - api-gateway/src/main/resources/application.properties:1
- Dashboard örnekleri:
  - docs/04-operations/02-monitoring/dashboards/grafana-dashboard.json:1 (user-service p95/p99)
  - docs/04-operations/02-monitoring/dashboards/gateway-grafana-dashboard.json:1 (gateway p95/p99, 4xx/5xx)

## 7) Güvenlik Özeti

- RS256 + JWKS; issuer=`http://localhost:8081/realms/serban`, jwk-set-uri=`http://localhost:8081/realms/serban/protocol/openid-connect/certs`, audience mapper `aud=["frontend","user-service"]`.
- Legacy service-token/internal JWT filtreleri yalnızca `@Profile({"local","dev"})` altında yüklenir; prod/test profilleri Keycloak JWT harici hiçbir token kabul etmez.
- Export guard: rate-limit + `X-PII-Policy: mask`
- AdvancedFilter: alan/operatör whitelist + tip güvenliği → hatalı/izinsiz durumlarda 400
  - Doküman: docs/agents/06-advanced-filter-whitelist.md:1
- Secrets: Prod/test profilleri Vault KV (`secret/db/<service>`, `secret/jwt/auth-service`) üzerinden `spring.config.import=vault://...` ile yüklenir ve `fail-fast=true`; local/dev profilleri Vault import satırı içermez.

## 8) Test ve Performans Kanıtları

- Güvenlik entegrasyon testleri (JWT 401/200/aud):
  - user-service/src/test/java/com/example/user/security/UserSecurityIntegrationTest.java:1
  - variant-service/src/test/java/com/example/variant/security/VariantSecurityIntegrationTest.java:1
  - api-gateway/src/test/java/com/example/apigateway/GatewaySecurityTest.java:1
- Performans:
  - Testcontainers Postgres p95: user-service/src/test/java/com/example/user/perf/UserServicePerformanceTest.java:1
  - k6 smoke: scripts/perf/k6-smoke.js:1 ve scripts/perf/perf-run.sh:1

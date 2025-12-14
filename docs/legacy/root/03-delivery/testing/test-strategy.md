---
title: "Test Stratejisi"
status: in-progress
owner: "@team/quality-arch"
last_review: 2025-11-08
tags: ["testing", "strategy"]
---

# Test Stratejisi

## Piramit

- Unit → Contract/Component → Integration → E2E → Perf/Security

## Zorunlu Kaplamalar

- Güvenlik entegrasyon: 401/403/200, audience doğrulama
- SSRM sözleşmesi: `sort` (çoklu ORDER BY), `advancedFilter` (whitelist + tip güvenliği)
- CSV export streaming: içerik başlığı ve chunked aktarım

## Performans

- Testcontainers + Postgres ile p95 doğrulaması (User list)
  - user-service/src/test/java/com/example/user/perf/UserServicePerformanceTest.java:1
- k6 smoke (50–100 RPS, 1–3 dk), 95p < 300 ms hedef
  - scripts/perf/k6-smoke.js:1, scripts/perf/perf-run.sh:1

## Gateway

- 4xx/5xx mapping ve header forward doğrulaması
  - api-gateway/src/test/java/com/example/apigateway/GatewaySecurityTest.java:1

## CI/CD

- Doc-impact etiketi: “doc-impact/no-doc-impact” ve ilgili mimari doküman güncellemeleri
- Kritik test sınıfları “must pass”: security/perf smoke; ağır Testcontainers testleri opsiyonel env ile koşar (PERF_TESTS=1)

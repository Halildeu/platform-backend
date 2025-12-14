---
title: "Güvenlik Mimarisi"
status: in-progress
owner: "@team/platform-arch"
last_review: 2025-11-08
tags: ["security", "architecture"]
---

# AGENT NOTU – Normatif Kurallar
> Bu dosya `docs/agents` altında yer alan resmi güvenlik mimarisi kural setlerinden biridir.  
> LLM agent, güvenlik/compliance ile ilgili SPEC, STORY veya RUNBOOK üretirken buradaki maddeleri “MUST” olarak kabul eder; yalnız uygular/özelleştirir, yeniden tanımlamaz.  
> Kural değişikliği gerekiyorsa bu dosyanın kendisi review/commit ile güncellenir; başka dokümanlarda override yapılmaz.

# Güvenlik Mimarisi

## Kimlik ve Erişim

- RS256 + JWKS doğrulama (gateway ve backend servisler resource server)
  - Gateway konfig: api-gateway/src/main/resources/application.properties:1
  - Servis konfig örn.: user-service/src/main/java/com/example/user/security/SecurityConfig.java:1, variant-service/src/main/java/com/example/variant/security/SecurityConfig.java:1
- Service‑to‑service token mint (client_credentials)
  - Endpoint: `POST /oauth2/token`
  - Kod: auth-service/src/main/java/com/example/auth/controller/ServiceTokenController.java:1
- Audience politikası: Rota/servis bazında `aud` beklenen değere valide edilir.

## Yetkilendirme

- Uygulama düzeyi izinler ve rol modeli; permission-service üzerinden yönetilir.
- Internal endpoint’lerde service token zorunluluğu (örn. `PERM_users:internal`).

## Veri Güvenliği

- Export guard: Gateway’de oran sınırlaması ve `X-PII-Policy: mask` başlığı zorunlu
  - Kod: api-gateway/src/main/java/com/example/apigateway/security/ExportGuardFilter.java:1
- AdvancedFilter whitelist + tip güvenliği
  - Doküman: docs/agents/06-advanced-filter-whitelist.md:1
  - Uygulama: user-service/src/main/java/com/example/user/controller/UserController.java:1
  - UI whitelist yardımcıları: packages/ui-kit/src/lib/filters/advancedFilter.ts:1

## Operasyonel Güvenlik

- Secret yönetimi: Env öncelikli; prod’da gizli bilgilerin kasası (örn. Vault) için ADR planlanır.
- Log’larda PII maskeleme (export guard ve uygulama seviyesinde)
- TLS/MTLS (gerektiğinde) ve dependency policy (supply chain) takibi için CI entegrasyonu planlı.

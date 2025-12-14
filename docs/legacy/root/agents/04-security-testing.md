---
title: "Güvenlik Test Stratejisi"
status: in-progress
owner: "@team/platform-arch"
last_review: 2025-11-08
tags: ["security", "testing"]
---

# AGENT NOTU – Normatif Kurallar
> Bu dosya `docs/agents` altında yer alan resmi güvenlik test stratejisi kural setlerinden biridir.  
> LLM agent, güvenlik/compliance ile ilgili SPEC, STORY veya RUNBOOK üretirken buradaki maddeleri “MUST” olarak kabul eder; yalnız uygular/özelleştirir, yeniden tanımlamaz.  
> Kural değişikliği gerekiyorsa bu dosyanın kendisi review/commit ile güncellenir; başka dokümanlarda override yapılmaz.

# Güvenlik Test Stratejisi

## Entegrasyon Testleri

- JWT 401/200 ve audience mismatch
  - user-service/src/test/java/com/example/user/security/UserSecurityIntegrationTest.java:1
  - variant-service/src/test/java/com/example/variant/security/VariantSecurityIntegrationTest.java:1
- Gateway
  - WebTestClient ile 401/200; 4xx/5xx propagate; export’ta `X-PII-Policy` header doğrulaması
  - api-gateway/src/test/java/com/example/apigateway/GatewaySecurityTest.java:1

## Gelişmiş Filtre (advancedFilter)

- Whitelist + tip güvenliği negatif senaryolar (invalid field/operator/type/inRange)
  - user-service/src/test/java/com/example/user/security/UserSecurityIntegrationTest.java:1

## Rate‑limit ve Guardrails

- ExportGuardFilter: 429 ve PII maskeleme header’ı
  - api-gateway/src/test/java/com/example/apigateway/security/ExportGuardFilterTest.java:1

## DAST/SAST ve CI

- SAST: bağımlılık taraması ve kod tarama job’ları (plan)
- DAST: smoke profilleri sonrası base keşif (k6/OWASP ZAP entegrasyonu plan)

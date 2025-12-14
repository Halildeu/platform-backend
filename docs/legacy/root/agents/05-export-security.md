# AGENT NOTU – Normatif Kurallar
> Bu dosya `docs/agents` altında yer alan resmi export güvenliği (CSV/Excel) kural setlerinden biridir.  
> LLM agent, güvenlik/compliance ile ilgili SPEC, STORY veya RUNBOOK üretirken buradaki maddeleri “MUST” olarak kabul eder; yalnız uygular/özelleştirir, yeniden tanımlamaz.  
> Kural değişikliği gerekiyorsa bu dosyanın kendisi review/commit ile güncellenir; başka dokümanlarda override yapılmaz.

## Export Güvenliği (CSV/Excel)

Amaç
- Büyük veri export işlemlerinde sistem ve veri güvenliğini sağlamak (rate‑limit, audit, PII maskeleme).

Öneriler
- Rate‑limit: Kullanıcı/rol bazında kota ve burst limit (Gateway/NGINX/Ingress)
- Audit: Export istekleri `who/when/filters/sort/rowCount` ile kayıt altına alınır
- PII: Gereksiz alanları maskeleyin veya hiç göndermeyin; export whitelist kullanın
- Streaming: CSV export’ta chunked/stream ile bellek sabit

Kabul Kriterleri
- Export uçları limitlidir; anormal kullanım alert’e düşer
- Audit logları export parametrelerini içerir
- Export içerikleri PII yönergesine uyumludur

Bağlantılar
- `frontend/docs/ag-grid-ssrm-export-strategy.md`
- `docs/01-architecture/04-security/guardrails/security-guardrails.md`
- `docs/04-operations/01-runbooks/81-export-guard-tuning.md`

Örnek (Spring Cloud Gateway — Redis Rate Limiter)
```
# Pom: spring-boot-starter-data-redis-reactive eklenmeli
# application.properties
# spring.cloud.gateway.routes[10].id=users-export-limit
# spring.cloud.gateway.routes[10].uri=lb://user-service
# spring.cloud.gateway.routes[10].predicates[0]=Path=/api/users/export.csv
# spring.cloud.gateway.routes[10].filters[0]=RequestRateLimiter=redis-rate-limiter.replenishRate=1,redis-rate-limiter.burstCapacity=2
# spring.cloud.gateway.routes[10].filters[1]=AddRequestHeader=X-PII-Policy,mask
# KeyResolver bean: principal/email bazlı (X-User-Email)
```

Minimal (dev/stage) in‑memory guard
- Prod’da Redis/Ingress tavsiye edilir; dev/stage için basit bir GlobalFilter eklendi:
  - Sınıf: `api-gateway/src/main/java/com/example/apigateway/security/ExportGuardFilter.java`
  - Env: `export.rate-limit.per-minute` (default 12), `export.rate-limit.burst` (default 24)
  - Hedef uçlar: `/api/users/export.csv`, `/api/audit/events/export`
  - Özellikler: IP/e‑posta (X-User-Email) anahtarıyla oran sınırlama, `X-PII-Policy: mask` başlığı ekleme, PII maskeli query log’ları

Backend guard (user-service)
- Amaç: Gateway korumasına ek olarak servis içinde kullanıcı bazlı rate-limit ve audit izi oluşturmak.
- Yaklaşım:
  - Token bucket (in memory) kullanıcı ID’sine göre anahtarlar; konfigürasyon `export.rate-limit.per-minute`, `export.rate-limit.burst` property’leri ile uyumludur.
  - Limit aşıldığında `429` ve `error=export_rate_limit` döner.
  - Audit: `UserAuditEventService` ile `eventType=USER_EXPORT` kaydı tutulur; `details` alanında `success`, `rows`, `durationMs`, `search/status/role/sort/advancedFilter` (maskeleme + truncate) ve hata mesajı yer alır.
  - Streaming tamamlandığında audit kaydı yazılır; hata durumunda `USER_EXPORT_FAILED` kaydı açılır.
  - CSV endpoint’i guard servisi üzerinden rate-limit kontrolü yapmadan çalışmaz.

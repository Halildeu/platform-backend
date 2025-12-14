---
title: "Audit Servis Tasarımı"
status: in_review
owner: "@team/platform-backend"
last_review: 2025-11-04
---

# Audit Servis Tasarımı

Bu taslak Sprint 4 kapsamındaki audit event feed’i besleyecek backend uçlarını tanımlar. `permission-service` içinde audit tablosu zaten bulunduğu için ilk iterasyonda mevcut servise REST API eklemek tercih edilir; ileride ayrık `audit-service` mikroservisine taşınabilir.

## 1. Veritabanı Modeli
- Tablo: `permission_audit_events`
  - `id` (BIGSERIAL, PK)
  - `event_type` (örn. `ASSIGN_ROLE`, `REVOKE_ROLE`)
  - `occurred_at` (TIMESTAMP WITH TIME ZONE, varsayılan `now()`)
  - `performed_by` (BIGINT, opsiyonel – kullanıcı ID)
  - `user_email` (karşı tarafı tanımlamak için `user:123` formatında)
  - `service` (örn. `permission-service`)
  - `level` (`INFO` / `WARN` / `ERROR`)
  - `action` (sunum katmanında kullanılan kısa kod: `ROLE_ASSIGNED`, `ROLE_REVOKED` ...)
  - `details` (VARCHAR 2000)
  - `correlation_id` (request/senaryo zincirini bağlamak için string)
  - `metadata` (TEXT, JSON string olarak saklanır)
  - `before_state` (TEXT, JSON string olarak saklanır)
  - `after_state` (TEXT, JSON string olarak saklanır)
- Index önerileri (kodda mevcut):
  - `idx_permission_audit_events_type` (`event_type`)
  - `idx_permission_audit_events_user` (`user_email`)
  - `idx_permission_audit_events_service` (`service`)

## 2. REST API
### 2.1 List Endpoint
- `GET /api/audit/events`
- Sorgu parametreleri:
  - `page` (varsayılan 0)
  - `size` (varsayılan 200, maksimum 500)
  - `sort` (örn. `timestamp,desc`)
  - `filter[userEmail]`
  - `filter[service]`
  - `filter[level]`
  - `filter[action]`
  - `filter[correlationId]`
  - `dateFrom`, `dateTo` (ISO-8601)
- Yanıt:
```json
{
  "events": [
    {
      "id": "...",
      "timestamp": "2025-11-04T08:12:11Z",
      "userEmail": "ops@example.com",
      "service": "permission-service",
      "level": "INFO",
      "action": "ROLE_UPDATED",
      "details": "Role X assigned to user Y",
      "metadata": { "roleId": "..." },
      "before": { "permissions": ["VIEW"] },
      "after": { "permissions": ["VIEW", "MANAGE"] },
      "correlationId": "abc-123"
    }
  ],
  "page": 0,
  "total": 3842
}
```

### 2.2 Live Stream
- `GET /api/audit/events/live`
- Server-Sent Events (SSE); her yeni event JSON olarak `data:` satırında gönderilir.
- İstemci tarafında `useAuditLiveStream` hook'u hata durumunda 15 sn aralıklarla `GET /api/audit/events` çağrısını yenileyerek fallback sağlar (ek bir endpoint gerekmez).

### 2.3 Export
- `GET /api/audit/events/export`
  - Parametreler: `format=csv|json`, mevcut filtrelerin tamamı (`filter[...]`, `sort`, `limit`).
  - CSV: UTF-8, `,` ayırıcı, sütunlar: `id,timestamp,userEmail,service,level,action,details,correlationId,metadata,beforeState,afterState`.
  - JSON: Dizi formatında `AuditEventResponse[]`.

## 3. Güvenlik
- Endpoint’ler `VIEW_AUDIT` izni gerektirir; `mfe-shell` bu izinle menüyü açar. (Spring Security konfigürasyonu tamamlanmalı.)
- SSE endpoint’i için access token doğrulaması; bağlantı kopuşunda tekrar bağlanma stratejisi.
- Export uçlarında rate limit (örn. IP başına dakikada 10) uygulanmalı. (TODO)

## 4. Telemetry & Loglama
- Her cevapta `X-Audit-Query-Duration` header (ms) döndürülebilir.
- Prometheus metricleri:
  - `audit_events_query_duration_seconds` (histogram)
  - `audit_events_stream_clients_total`
- Audit event oluşturma pipeline’ı halihazırda permission-service içinde (`PermissionAuditEvent`) devam eder; `PermissionService` çağrıları correlation ID üretip SSE yayını tetikler.

## 5. Açık Sorular
- Ayrı audit mikroservisine ne zaman çıkarılacak? (ADR önerisi gerekebilir.)
- Diff kolonları büyük payload’larda kırpılmalı mı? (örn. 1 MB sınır.)
- Export dosyalarının saklama süresi (geçici dosya mı, streaming mi?)

## 6. Yol Haritası
1. ✅ `permission-service` içinde DTO + repository + controller implementasyonu.
2. SSE endpoint’inin load test’i; 100 eşzamanlı client hedefi.
3. Ops pipeline’ında alert eklenmesi (Grafana audit dashboard planı).

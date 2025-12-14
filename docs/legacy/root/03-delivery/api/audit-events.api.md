## Audit Events API Sözleşmesi

Amaç
- MFE’lerde yapılan kritik işlemlerin izlenebilmesi için audit event’lerinin sorgulanması ve deep‑link’lenmesi.

 Listeleme
- Method: GET
- Path: `/api/audit/events`
- Query Parametreleri:
  - `page` (number, >=1)
  - `pageSize` (number, 1..1000)
  - `from` (ISO datetime) — başlangıç zamanı
  - `to` (ISO datetime) — bitiş zamanı
  - `user` (string, opsiyonel) — aktör e‑posta/kimlik
  - `service` (string, opsiyonel) — kaynak servis adı (permission-service, user-service, …)
  - `level` (enum, opsiyonel) — `INFO|WARN|CRITICAL`
  - `id` (string, opsiyonel) — belirli bir olayı çekmek/öne çıkarmak için auditId
  - `search` (string, opsiyonel) — metin arama (kaynak, aktör, özet)
  - `sort` (string, opsiyonel) — `field,dir` formatı; desteklenen alanlar: `occurredAt|userEmail|service|level|action|correlationId`. `dir`: `asc|desc`. Varsayılan: `occurredAt,desc`.

 Desteklenen alias/legacy anahtarlar
 - `from` → `dateFrom`, `to` → `dateTo`
 - `user` → `userEmail`
 - `filter[<key>]` — eski filtre formatı (örn. `filter[action]=ASSIGN_ROLE`)

 Yanıt (liste)
```json
{
  "events": [
    {
      "id": "a-123",
      "timestamp": "2025-11-03T10:15:30.000Z",
      "userEmail": "a***@example.com",
      "service": "permission-service",
      "action": "ASSIGN_ROLE",
      "level": "INFO",
      "details": "Role ADMIN assigned to user",
      "correlationId": "corr-123",
      "metadata": { "userId": 42, "email": "a***@example.com" },
      "before": { "name": "J***" },
      "after": { "name": "J***", "addressLine": "S***" }
    }
  ],
  "page": 0,
  "total": 1
}
```

Notlar
- `page` alanı 0‑bazlıdır (0 ilk sayfa); UI tarafında sayfa numarası gösterilirken 1 eklenebilir.

 Export
- Method: GET
- Path: `/api/audit/events/export`
- Query: `format=csv|json` (default: `json`), `limit` (opsiyonel), `sort`, listeleme ile aynı filtreler.
- Response: Dosya indirme (`Content-Disposition`) — `audit-events.csv|json`.

 Canlı Yayın (SSE)
- Method: GET
- Path: `/api/audit/events/live`
- Content-Type: `text/event-stream`
- Açıklama: Sunucu taraflı olayları gerçek zamanlı aktarır; bağlantı koparsa UI polling’e geri düşebilir.

Deep‑Link
- UI deep‑link örneği: `/admin/audit?event=a-123` (alternatif: `?auditId=a-123`)
- Audit MFE, `event` veya `auditId` query param’ını alır; grid’de ilgili olayı vurgular ve drawer’ı açar.

 Güvenlik
- Header: `Authorization: Bearer <jwt>`
- (opsiyonel) `X-Internal-Api-Key` — iç API’lerde fazladan doğrulama için
- Yanıt içindeki PII alanları (email, name, phone, address, token, identifier, IP vb.) sunucu tarafında maskelenir. E‑posta adresleri `a***@example.com` formatında kısaltılır; diğer string değerler ilk karakter + `***` ile gösterilir.

 Kabul Kriterleri
- `id` param’ı verildiğinde tek olay döner (response `page=0`, `total=1`) veya 404 (bulunamadı).
- Zaman aralığı/sayfalama deterministik; ORDER BY `ts desc` varsayılan.
- PII alanları maskelenir; payload diff’te yalnızca gerekli alanlar gösterilir.

Hata Durumları
- 400 `invalid_filter` — `from/to` tarihleri hatalı veya desteklenmeyen filtre kombinasyonu.
- 401 `unauthorized` — geçersiz/eksik `Authorization` header.
- 429 `too_many_requests` — export endpoint’i rate‑limit nedeniyle engellendiğinde.

Notlar
- `filter[<key>]`, `dateFrom/dateTo`, `action`, `correlationId` gibi legacy anahtarlar geriye dönük uyumluluk için desteklenir.

Bağlantılar
- `docs/01-architecture/03-services/02-permission-service.md`
- `docs/01-architecture/03-services/01-user-service.md`

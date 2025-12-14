# SPEC-E06-S01-TELEMETRY-OBSERVABILITY-V1
**Başlık:** Telemetry & Observability Korelasyonu v1.0  
**Versiyon:** v1.0  
**Tarih:** 2025-11-19  

**İlgili Dokümanlar:**  
- EPIC: `docs/05-governance/01-epics/E06_TelemetryAndObservability.md`  
- ADR:  
  - `docs/05-governance/05-adr/ADR-004-telemetry-and-audit-taxonomy.md`  
  - `docs/05-governance/05-adr/ADR-011-observability-correlation.md`  
- ACCEPTANCE: `docs/05-governance/07-acceptance/E06-S01-Telemetry-and-Observability.acceptance.md`  
- STORY: `docs/05-governance/02-stories/E06-S01-Telemetry-and-Observability.md`  
- STYLE GUIDE: `docs/00-handbook/NAMING.md`  

---

# 1. Amaç (Purpose)

Telemetry & audit taksonomisini (ADR-004) ve FE↔BE trace korelasyonunu (ADR-011) uygulayarak:
- TTFA (Time To First Action) ve hata oranı gibi deneyim metriklerini ölçmek,
- FE event’lerini backend trace’leri ve audit kayıtlarıyla ilişkilendirmek,
- PII redaction ve retention kurallarıyla uyumlu bir telemetry pipeline’ı sağlamak.

---

# 2. Kapsam (Scope)

### Kapsam içi
- FE event taksonomisi (`page_view`, `action_click`, `mutation_commit`, `ttfa_event` vb.) ve zorunlu boyutlar.
- `auditId` tabanlı mutasyon → audit trail zinciri.
- W3C Trace Context (`traceparent`, `tracestate`) ve correlation-id yayılımı.
- Tempo/Loki/Grafana panoları ve temel SLO/alert setleri.

### Kapsam dışı
- Tüm servisler için ayrıntılı metric enstrümantasyonu (servis bazlı detaylar ileriki Story’lerde ele alınır).
- İş zekâsı/raporlama katmanı (ayrı reporting epikleri altında tanımlanır).

---

# 3. Tanımlar (Definitions)

- **Telemetry Event:** Kullanıcı veya sistem davranışını temsil eden, şemalı log/enstrümantasyon kaydı.  
- **TTFA (Time To First Action):** Kullanıcı ilk anlamlı etkileşimini (örn. buton tıklaması, filtre uygulaması) gerçekleştirene kadar geçen süre.  
- **Trace:** Bir isteğin uçtan uca seyrini gösteren, span’lerden oluşan zincir.  
- **auditId:** Kullanıcı aksiyonunu, backend mutasyonu ve audit log kaydını birbirine bağlayan benzersiz kimlik.  

---

# 4. Kullanıcı Senaryoları (User Flows)

1. **Sayfa Görüntüleme ve TTFA Ölçümü**
   - Kullanıcı shell üzerinden bir sayfaya girer; FE `page_view` event’i üretir.
   - İlk anlamlı aksiyonda (örn. filtre uygulama) `ttfa_event` üretilir; TTFA değeri event payload’ında taşınır.

2. **Mutasyon → Audit Zinciri**
   - Kullanıcı bir form submit eder; FE mutasyon request’ine `auditId` ekler.
   - BE, aynı `auditId` ile mutasyon log’u ve audit event’i üretir.
   - Tempo/Loki üzerinden `auditId` ile arama yapıldığında, FE event’i, backend trace’i ve audit kaydı birlikte görülebilir.

3. **Hata ve Fallback Telemetry’si**
   - MFE’de beklenmeyen hata oluşur; FE `error_event` üretir ve TTFA/auditId bilgilerini ekler.
   - Backend, 5xx veya iş kuralı hatalarında ilgili telemetry event’lerini loglar.

---

# 5. Fonksiyonel Gereksinimler (Functional Requirements)

**FR-OBS-01:** Tüm kritik kullanıcı akışlarında `page_view`, `ttfa_event` ve `action_click` event’leri üretilmeli ve TTFA hesaplanabilir olmalıdır.  
**FR-OBS-02:** Her mutasyon isteği (POST/PUT/DELETE) için `auditId` üretilmeli ve FE→BE→audit zincirinde taşınmalıdır.  
**FR-OBS-03:** Trace context (traceparent, tracestate) FE’den BE’ye ve gerekli alt servis çağrılarına yayılmalıdır.  
**FR-OBS-04:** Tempo/Loki panolarında en az bir uçtan uca akış (örn. login + kritik aksiyon) auditId/traceID ile izlenebilir olmalıdır.  

---

# 6. İş Kuralları (Business Rules)

**BR-OBS-01:** Telemetry event şemaları versioned olmalı; breaking değişiklikler major versiyon gerektirir.  
**BR-OBS-02:** PII alanları (örn. TC no, e‑posta, telefon) telemetry payload’larında redakte edilmeli veya hashlenmelidir; ham PII loglanmamalıdır.  
**BR-OBS-03:** TTFA metrikleri için hedef: soğuk açılış p95 ≤ 8s, sıcak açılış p95 ≤ 3s. Metrik ihlalinde alert üretilmelidir.  

---

# 7. Veri Modeli (Data Model)

## 7.1. FE Telemetry Event (Örnek JSON)

```json
{
  "eventType": "page_view",
  "page": "/admin/users",
  "timestamp": "2025-11-19T10:15:00Z",
  "sessionId": "sess-123",
  "userId": "user-456",
  "traceId": "abcd-efgh",
  "metadata": {
    "variant": "default",
    "featureFlags": ["grid:users:export"]
  }
}
```

## 7.2. Mutasyon/Audit Event (Örnek JSON)

```json
{
  "eventType": "mutation_commit",
  "auditId": "audit-789",
  "operation": "users.update",
  "timestamp": "2025-11-19T10:16:00Z",
  "traceId": "abcd-efgh",
  "result": "success"
}
```

---

# 8. API Tanımı (API Spec)

Telemetry ve trace verileri OTEL collector / Loki / Tempo üzerinden taşınır; bu SPEC yeni HTTP API tanımlamaz.  
Eğer gerektiğinde internal query endpoint’leri tanımlanacaksa:

- `/internal/telemetry/ttfa/summary`
- `/internal/telemetry/audit/trail?auditId=<id>`

gibi endpoint’ler sade JSON dönecek şekilde tasarlanır.

---

# 9. Validasyon Kuralları (Validation Rules)

- `eventType`: Öncetenden tanımlı değerlerden biri olmalıdır (`page_view`, `action_click`, `mutation_commit`, `ttfa_event`, `error_event`...).  
- `auditId`: Non‑empty string ve sistem genelinde benzersiz olmalıdır (en azından belirli bir süre için).  
- PII alanları, telemetry payload’larına girildiğinde redaction filtresinden geçmelidir.  

---

# 10. Hata Kodları (Error Codes)

Bu SPEC yeni external error code seti tanımlamaz; ancak internal alert kodları şu şekilde gruplanabilir:

| Kod            | Açıklama                                |
|----------------|-----------------------------------------|
| OBS_TTFA_HIGH  | TTFA değeri tanımlı eşiğin üzerinde     |
| OBS_TRACE_GAP  | Trace zincirinde eksik span / kopukluk  |
| OBS_PII_LEAK   | Telemetry payload’larında PII tespit edildi |

---

# 11. Non-Fonksiyonel Gereksinimler (NFR)

- Telemetry pipeline’ı, istek süresine anlamlı yük bindirmemeli (örn. ek latency < 50ms).  
- Telemetry sistemleri için retention ve erişim izinleri güvenlik/compliance gereksinimlerine uygun olmalıdır.  

---

# 12. İzlenebilirlik (Traceability)

Bu spekten türeyen dokümanlar:
- Story: `docs/05-governance/02-stories/E06-S01-Telemetry-and-Observability.md`  
- Acceptance: `docs/05-governance/07-acceptance/E06-S01-Telemetry-and-Observability.acceptance.md`  
- ADR:  
  - `docs/05-governance/05-adr/ADR-004-telemetry-and-audit-taxonomy.md`  
  - `docs/05-governance/05-adr/ADR-011-observability-correlation.md`  

Bu doküman, telemetry & observability korelasyonu v1.0 için teknik tasarımın tek kaynağıdır.


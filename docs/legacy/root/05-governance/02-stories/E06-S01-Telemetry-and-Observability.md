# Story E06-S01 – Telemetry & Observability Korelasyonu v1.0

- Epic: E06 – Telemetry & Observability  
- Story Priority: 610  
- Tarih: 2025-12-06  
- Durum: In Progress

## Kısa Tanım

Telemetry & audit taksonomisini hayata geçirip W3C Trace Context ile FE↔BE zincirini birleştirerek TTFA, hata oranı ve fallback oranını uçtan uca izlenebilir hale getirmek.

## İş Değeri

- Ürün deneyimi sayfa/aksiyon bazında ölçülür; riskli alanlar veriyle tespit edilir.
- FE event’leri ile backend trace ve log’ları aynı trace-id üzerinden takip edilebilir.
- Audit ve telemetry verileri KVKK/GDPR beklentilerine uygun şekilde redaction ve retention kurallarıyla yönetilir.

## Bağlantılar (Traceability Links)

- SPEC: `docs/05-governance/06-specs/SPEC-E06-S01-TELEMETRY-OBSERVABILITY-V1.md`  
- ACCEPTANCE: `docs/05-governance/07-acceptance/E06-S01-Telemetry-and-Observability.acceptance.md`  
- ADR: ADR-004 (Telemetry & Audit Taksonomisi), ADR-011 (Observability Korelasyonu)  
- STYLE GUIDE: `docs/00-handbook/NAMING.md`

## Kapsam

### In Scope
- Temel event taksonomisi: `page_view`, `action_click`, `mutation_commit` ve TTFA ölçümleri için şema tanımı.
- Telemetry boyutları: `pageId`, `entity`, `result`, `duration`, `traceId`, `flagId` vb. alanların standardizasyonu.
- Mutasyon yanıtlarında `auditId` alanının zorunlu hale getirilmesi ve notification/detail ekranlarından audit ekranına linklenmesi.
- Shell ve MFE’lerde W3C Trace Context (`traceparent`, `tracestate`) başlıklarının üretilmesi/taşınması.
- Grafana Tempo + Loki + Prometheus panolarının TTFA, hata oranı, fallback oranı ve telemetry sampling için hazırlanması.

### Out of Scope
- Tüm legacy endpoint’lerin tek seferde OTEL’e taşınması (öncelikle kritik akışlar kapsanır).
- Her modül için ayrı detaylı observability runbook’ları (ayrı ops dokümanlarında ele alınır).

## Task Flow (Ready → InProgress → Review → Done)

```text
+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
| Task                                                        | Ready        | InProgress    | Review       | Done        |
+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
| Telemetry & audit event taksonomisinin tasarlanması         | 2025-12-06   | 2025-12-06    | 2025-12-06   | 2025-12-06  |
| FE tarafında temel telemetry event logging’in implementasyonu| 2025-12-06  |               |              |             |
| Backend trace/log’larda W3C Trace Context’in taşınması      | 2025-12-06   |               |              |             |
| Mutasyon yanıtlarında auditId alanının zorunlu hale getirilmesi| 2025-12-06 |               |              |             |
| Grafana Tempo/Loki/Prometheus panolarının hazırlanması      | 2025-12-06   |               |              |             |
| Telemetry/trace için test ve CI kontrollerinin eklenmesi    | 2025-12-06   |               |              |             |
+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
```

**Kural:**
- İlk tarih “Ready” → görev hazır  
- İkinci tarih “InProgress” → çalışma başladı  
- Üçüncü tarih “Review” → kod kontrol aşamasında  
- Son tarih “Done” → tamamen tamamlandı  
- Soldaki sütun dolmadan sağdaki sütun doldurulmaz (Ready → InProgress → Review → Done akışına uyulur).

## Fonksiyonel Gereksinimler

1. TTFA metrikleri soğuk ve sıcak durumlar için p95 hedefleriyle telemetry’de raporlanmalıdır.
2. FE event’leri ve backend trace’leri aynı `traceId` üzerinden Tempo’da korele edilebilir olmalıdır.
3. Mutasyon sonrası dönen `auditId` ile audit ekranına en az bir akıştan (örn. notification center) tek tıkla geçiş yapılmalıdır.
4. Telemetry event şeması versiyonlanmalı; breaking değişiklikler CI’da kontrol edilmelidir.

## Non-Functional Requirements

- Telemetry hacmi, sampling ve veri saklama ayarlarıyla kontrol altında tutulmalı; maliyetler izlenmelidir.
- PII redaction kuralları uygulanmalı; hassas alanlar kayıt altına alınmamalıdır veya maske ile tutulmalıdır.

## Acceptance Criteria

Bu Story için güncel acceptance kriterleri:  
`docs/05-governance/07-acceptance/E06-S01-Telemetry-and-Observability.acceptance.md`

## Definition of Done

- [ ] Telemetry & observability acceptance maddeleri sağlanmış olmalı.  
- [ ] Acceptance dosyasındaki checklist (`docs/05-governance/07-acceptance/E06-S01-Telemetry-and-Observability.acceptance.md`) tam karşılanmış olmalı.  
- [ ] ADR-004/011 kararları uygulanmış olmalı.  
- [ ] Kod, `docs/00-handbook/NAMING.md` ve stil kurallarına uyumlu olmalı.  
- [ ] Kod review’dan onay almış olmalı.  
- [ ] OTEL/trace/log testleri yeşil olmalı.  
- [ ] `docs/05-governance/PROJECT_FLOW.md` üzerinde Son Durum ✔ Done olarak işlenmiş olmalı.

## Notlar

- PII redaction ve retention kuralları zaman içinde regülasyona göre güncellenebilir; değişiklikler acceptance’a yansıtılmalıdır.

## Dependencies

- ADR-004 – Telemetry & Audit Taksonomisi.
- ADR-011 – Observability Korelasyonu.

## Risks

- Telemetry şemasının hızlı değişmesi; dashboard ve alarm tanımlarının geride kalması.
- Trace-id’nin tüm katmanlarda taşınmaması nedeniyle eksik korelasyon.

## Flow / Iteration İlişkileri

| Flow ID   | Durum    | Not                                                          |
|-----------|---------|--------------------------------------------------------------|
| Flow-03   | Planned | Telemetry taksonomisi ve FE↔BE trace korelasyonunun ilk dalgası. |

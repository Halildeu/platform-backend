# Epic E06 – Telemetry & Observability

- Epic Priority: 600  
- Durum: In Progress

## Açıklama

Telemetry & audit taksonomisi, TTFA ölçümü ve FE↔BE trace/log korelasyonunu kapsar. Amaç, kullanıcı davranışını ve sistem sağlığını uçtan uca izlenebilir hale getirmektir.

EPIC_BUSINESS_CONTEXT:
- Ürün deneyimini TTFA, hata oranı ve fallback oranı ile objektif ölçmek.
- FE event’lerini backend trace’leriyle uçtan uca takip edebilmek.
- Denetlenebilir audit izi ve PII redaction ile regülasyonlara uyum.

## Fonksiyonel Kapsam

- Event taksonomisi (`page_view`, `action_click`, `mutation_commit`, TTFA vb.) ve zorunlu boyutlar.
- `auditId` tabanlı mutasyon/audit zinciri.
- W3C Trace Context (`traceparent`, `tracestate`) ve correlation-id yayılımı.
- Grafana Tempo/Loki panoları ve SLO/alert setleri.

## Non-Functional Requirements (Epic Seviyesi)

- TTFA p95 hedefleri: soğuk ≤ 8 sn, sıcak ≤ 3 sn.
- Telemetry event ve trace alanları için versioned şema; geriye dönük uyum.

## Story Listesi

| Story ID | Story Adı                                  | Durum    | Story Dokümanı                                       |
|----------|--------------------------------------------|---------|------------------------------------------------------|
| E06-S01  | Telemetry & Observability Korelasyonu v1.0 | Planned | 02-stories/E06-S01-Telemetry-and-Observability.md    |

## Doküman Zinciri (Traceability)

- Epic: `docs/05-governance/01-epics/E06_TelemetryAndObservability.md`
- Story:
  - `docs/05-governance/02-stories/E06-S01-Telemetry-and-Observability.md`
- SPEC:
  - `docs/05-governance/06-specs/SPEC-E06-S01-TELEMETRY-OBSERVABILITY-V1.md`
- Acceptance:
  - `docs/05-governance/07-acceptance/E06-S01-Telemetry-and-Observability.acceptance.md`
- ADR:
  - `docs/05-governance/05-adr/ADR-004-telemetry-and-audit-taxonomy.md`
  - `docs/05-governance/05-adr/ADR-011-observability-correlation.md`

## Story–Sprint Eşleştirmeleri

| Story ID | Sprint ID | Not                                             |
|----------|-----------|-------------------------------------------------|
| E06-S01  | (TBD)     | Telemetry taksonomisi + trace korelasyonu ilk dalga |

## Bağımlılıklar

- OTEL backend altyapısı (Tempo/Loki/Prometheus).
- Security ve legal ekiplerinin PII/redaction kuralları.

## Riskler

- Event şemasının aşırı büyümesi; kırılgan şema değişiklikleri.
- FE ↔ BE trace bağlamının eksik taşınması ve boş zincirler.

## İlgili Artefaktlar

- `docs/05-governance/05-adr/ADR-004-telemetry-and-audit-taxonomy.md`
- `docs/05-governance/05-adr/ADR-011-observability-correlation.md`

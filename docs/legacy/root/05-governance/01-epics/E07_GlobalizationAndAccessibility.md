# Epic E07 – Globalization & Accessibility

- Epic Priority: 700  
- Durum: In Progress

## Açıklama

Çok dilli sözlük paketleme pipeline’ı, pseudolocale testleri ve erişilebilirlik süreç standardını kapsar. Amaç, manifest tabanlı sayfaların i18n ve WCAG-AA hedeflerine sürdürülebilir şekilde ulaşmasını sağlamaktır.

EPIC_BUSINESS_CONTEXT:
- TR/EN/DE/ES ve ileride eklenecek diller için tutarlı i18n deneyimi.
- A11y bulgularının ayrı bir backlog ve SLA ile yönetilmesi.
- Ürün demolarında ve denetimlerde i18n/a11y durumunun güvenle gösterilebilmesi.

## Fonksiyonel Kapsam

- TMS (Tolgee/Weblate) tabanlı sözlük pipeline’ı ve `@mfe/i18n-dicts` paketleri.
- Pseudolocale ve fallback test akışları; eksik/yanlış çeviri telemetry’si.
- A11y checklist’i (screen reader turu, keyboard trap, kontrast ölçümleri).
- CI’da otomatik a11y kontrolleri (axe-core) ve manuel test süreci.

## Non-Functional Requirements (Epic Seviyesi)

- Eksik çeviri/fallback oranı telemetry’de < %1 hedefi.
- Kritik a11y bug’larının SLA içinde (örn. bir akış döngüsü) kapanması.

## Story Listesi

| Story ID | Story Adı                                  | Durum    | Story Dokümanı                                                |
|----------|--------------------------------------------|---------|---------------------------------------------------------------|
| E07-S01  | i18n & Erişilebilirlik Süreçleri v1.0      | Planned | 02-stories/E07-S01-Globalization-and-Accessibility.md         |

## Doküman Zinciri (Traceability)

- Epic: `docs/05-governance/01-epics/E07_GlobalizationAndAccessibility.md`
- Story:
  - `docs/05-governance/02-stories/E07-S01-Globalization-and-Accessibility.md`
- SPEC:
  - `docs/05-governance/06-specs/SPEC-E07-S01-GLOBALIZATION-A11Y-V1.md`
- Acceptance:
  - `docs/05-governance/07-acceptance/E07-S01-Globalization-and-Accessibility.acceptance.md`
- ADR:
  - `docs/05-governance/05-adr/ADR-008-i18n-and-dictionary-packaging.md`
  - `docs/05-governance/05-adr/ADR-014-accessibility-process-standard.md`

## Story–Flow Eşleştirmeleri

| Story ID | Flow ID | Not                                                    |
|----------|--------|--------------------------------------------------------|
| E07-S01  | (TBD)  | i18n sözlük pipeline’ı + a11y süreçlerinin ilk dalgası |

## Bağımlılıklar

- Theme & Layout (E03) ve grid deneyimi (E02).
- Observability (E06) tarafındaki telemetry alanları.

## Riskler

- TMS ve kod tarafındaki key setlerinin drift etmesi.
- A11y kontrollerinin akış baskısıyla atlanması.

## İlgili Artefaktlar

- `docs/05-governance/05-adr/ADR-008-i18n-and-dictionary-packaging.md`
- `docs/05-governance/05-adr/ADR-014-accessibility-process-standard.md`

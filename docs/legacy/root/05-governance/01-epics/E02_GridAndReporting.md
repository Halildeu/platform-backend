# Epic E02 – Grid & Reporting

- Epic Priority: 200  
- Durum: In Progress

## Açıklama

Grid altyapısını ve raporlama ekranlarını yöneten Epic’tir. Tüm MFE’lerde AG Grid standardının uygulanması, UI Kit `EntityGridTemplate` mimarisinin kurulması ve SSRM param sözleşmesinin FE/BE tarafında oturtulmasını kapsar.

EPIC_BUSINESS_CONTEXT:
- Yönetim ekranlarının tamamında tutarlı grid deneyimi.
- Büyük veri setlerinde performanslı, erişilebilir veri sunumu.
- Grid davranışının (tema, i18n, filtre, sort, pagination) tek merkezden yönetilmesi.

## Fonksiyonel Kapsam

- UI Kit `EntityGridTemplate` bileşeni ve AG Grid konfigürasyonu.
- Users/Access/Reporting MFE’lerindeki grid ekranları (liste sayfaları, rapor tabloları).
- Grid parametre sözleşmesi: `search`, `advancedFilter`, `sort`, `page/pageSize`.

## Non-Functional Requirements (Epic Seviyesi)

- Perf: İlk render sıcak durumda p95 < 2.5s; bundle boyutu Shell < 250KB gzip, her MFE < 300KB gzip (ilk route).
- Erişilebilirlik: WCAG-AA, tam klavye gezinme, focus-trap, aria etiketleri.

## Story Listesi

| Story ID | Story Adı                                      | Durum        | Story Dokümanı                                      |
|----------|-----------------------------------------------|-------------|-----------------------------------------------------|
| E02-S01  | AG Grid Standardı & Deneyim Bütçeleri         | In Progress | 02-stories/E02-S01-AG-Grid-Standard.md              |
| E02-S02  | Grid Mimarisi (UI Kit EntityGridTemplate + SSRM) | In Progress | 02-stories/E02-S02-Grid-UI-Kit-SSRM.md              |

## Doküman Zinciri (Traceability)

- Epic: `docs/05-governance/01-epics/E02_GridAndReporting.md`
- Story:
  - `docs/05-governance/02-stories/E02-S02-Grid-UI-Kit-SSRM.md`
- SPEC:
  - `docs/05-governance/06-specs/SPEC-E02-S02-GRID-REPORTING-V1.md`
- Acceptance:
  - `docs/05-governance/07-acceptance/E02-S02-Grid-UI-Kit-SSRM.acceptance.md`
- ADR:
  - `docs/05-governance/05-adr/ADR-005-ag-grid-standard-and-experience-budgets.md`
  - `docs/05-governance/05-adr/ADR-015-grid-mimarisi-ui-kit-ssrm.md`

## Story–Sprint Eşleştirmeleri

| Story ID | Sprint ID | Not                                    |
|----------|-----------|----------------------------------------|
| E02-S01  | (TBD)     | Grid perf/a11y bütçeleri + testler     |
| E02-S02  | (TBD)     | UI Kit grid + SSRM tam entegrasyonu    |

## Bağımlılıklar

- ADR-005 – AG Grid Standardı & Deneyim Bütçeleri.
- ADR-015 – Grid Mimarisi (UI Kit + SSRM).
- Backend API sözleşmeleri (`users.api.md`, `reporting-schema-contract.md`).

## Riskler

- Farklı MFE’lerde grid davranışının parçalı uygulanmaya devam etmesi (standart dışı konfigler).
- Perf/a11y testlerinin CI’da eksik kalması veya devre dışı bırakılması.

## İlgili Artefaktlar

- `docs/05-governance/05-adr/ADR-005-ag-grid-standard-and-experience-budgets.md`
- `docs/05-governance/05-adr/ADR-015-grid-mimarisi-ui-kit-ssrm.md`
- `frontend/docs/01-architecture/02-ui-kit/02-ag-grid-theme.md`

# Epic E03 – Theme & Shell Layout

- Epic Priority: 300  
- Durum: In Progress

## Açıklama

Tema (appearance, mode, density vb.) ve Shell layout (Topbar/Sidebar/PageLayout) sistemini yöneten Epic’tir. Figma token’ları, CSS var’lar, Tailwind config ve UI Kit primitives arasında tek bir tema modeli kurulmasını ve Ant Design’dan Tailwind-only yapıya geçişi kapsar.

EPIC_BUSINESS_CONTEXT:
- Kurumsal/gündüz/gece/high-contrast gibi tema profilleri için tek kaynaklı görünüm.
- Shell + MFE’lerde tutarlı layout ve tema davranışı.
- Tasarım ↔ kod drift’ini azaltmak; tema değişikliklerini güvenle yaymak.

## Fonksiyonel Kapsam

- Tema modelinin tanımı (`data-theme`, `data-mode`, semantic token’lar).
- UI Kit primitives ve Shell layout bileşenleri (Topbar, Sidebar, PageLayout).
- Ant Design bağımlılıklarının kaldırılması ve Tailwind-only UI Katmanı.
 - Renk Sistemi & Kontrast Rehberi: Light/Dark/High-Contrast tema profilleri için surface/text/icon/border/interactive/state/selection/focus/disabled/muted semantic renk haritası ve net kontrast hedefleri.
 - Figma Kit – Shell Layout (v1.0): Figma tarafında sayfa hiyerarşisi (Overview, Tokens, Foundations, Components, Patterns, Layouts, Docs, Assets) ve Shell/Topbar/Sidebar/Page Header/Filter Bar/Data Table bileşenlerinin token/variant kuralları.
 - AG Grid görsel standardı ile entegrasyon: Data Table (AG Grid pattern) için başlık, pinned sütun, grup başlığı, satır durumları (hover/selected/edit/error/readonly), zebra, gridline, sticky ve boş/hata/yükleniyor ekranlarının semantik renk haritası ile hizalanması.

## Non-Functional Requirements (Epic Seviyesi)

- Tema değişiminde minimal reflow; CSS var tabanlı boyama öncelikli.
- High-contrast modunda AAA seviyesinde kontrast; a11y testleri otomatik.
 - Renk erişilebilirliği için Figma içi test akışları, renk körlüğü simülasyonları ve onay kapısı; tema değişiklikleri bu testler geçmeden prod’a taşınmaz.
- Resmî tema setleri (`shell-light`, `shell-dark`, `shell-hc`, `shell-compact`) için appearance × density × radius × elevation × motion kombinasyonlarının Figma tema kartları ve AG Grid test panosu üzerinden doğrulanması.

## Story Listesi

| Story ID | Story Adı                              | Durum        | Story Dokümanı                                      |
|----------|----------------------------------------|-------------|-----------------------------------------------------|
| E03-S01  | Theme & Layout System v1.0 (Tailwind + Figma Tokens) | In Progress | 02-stories/E03-S01-Theme-Layout-System.md          |

## Doküman Zinciri (Traceability)

- Epic: `docs/05-governance/01-epics/E03_ThemeAndLayout.md`
- Story:
  - `docs/05-governance/02-stories/E03-S01-Theme-Layout-System.md`
- SPEC:
  - `docs/05-governance/06-specs/SPEC-E03-S01-THEME-LAYOUT-V1.md`
- Acceptance:
  - `docs/05-governance/07-acceptance/E03-S01-Theme-Layout-System.acceptance.md`
- ADR:
  - `docs/05-governance/05-adr/ADR-016-theme-layout-system.md`
 - Feature Request:
   - `docs/05-governance/FEATURE_REQUESTS.md` (FR-005 – Theme & Shell Layout Figma Kit v1.0)

## Story–Sprint Eşleştirmeleri

| Story ID | Sprint ID | Not                                      |
|----------|-----------|------------------------------------------|
| E03-S01  | (TBD)     | Tema modelinin ve Shell layout entegrasyonunun ilk dalgası |

## Bağımlılıklar

- ADR-016 – Theme & Layout System v1.0.
- Figma Kit tema/token dokümanları.
- UI Kit ve Shell kod tabanı (Tailwind config, design-system CSS var’ları).

## Riskler

- Ant → Tailwind geçişi sırasında UI kırıkları; tema değişiminde regressions.
- Figma token seti ile kod tarafındaki semantic token haritasının drift etmesi.

## İlgili Artefaktlar

- `docs/05-governance/05-adr/ADR-016-theme-layout-system.md`
- `docs/01-architecture/01-system/legacy-ant-design-tailwind-mapping.md`

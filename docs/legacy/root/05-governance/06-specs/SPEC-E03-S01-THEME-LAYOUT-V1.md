# SPEC-E03-S01-THEME-LAYOUT-V1
**Başlık:** Theme & Shell Layout System v1.0  
**Versiyon:** v1.0  
**Tarih:** 2025-11-19  

**İlgili Dokümanlar:**  
- EPIC: `docs/05-governance/01-epics/E03_ThemeAndLayout.md`  
- ADR:  
  - `docs/05-governance/05-adr/ADR-016-theme-layout-system.md`  
  - `docs/05-governance/05-adr/ADR-019-theme-ssot.md`  
- ACCEPTANCE: `docs/05-governance/07-acceptance/E03-S01-Theme-Layout-System.acceptance.md`  
- STORY: `docs/05-governance/02-stories/E03-S01-Theme-Layout-System.md`  
- STYLE GUIDE: `docs/00-handbook/NAMING.md`  
 - FEATURE REQUEST: `docs/05-governance/FEATURE_REQUESTS.md` (FR-005 – Theme & Shell Layout Figma Kit v1.0)  

---

# 1. Amaç (Purpose)

Tema modeli (appearance, mode, density vb.) ve Shell layout (Topbar/Sidebar/PageLayout) sistemini teknik olarak tanımlayarak, Ant Design’dan Tailwind-only yapıya geçişi (ADR-016) tamamlamak.  
Ayrıca Figma Kit – Shell Layout (v1.0) için token/semantic stil sözleşmesini ortaya koyar ve AG Grid dahil tüm bileşenlerde renk/kontrast kurallarının kod tarafında uygulanmasını tarif eder.
Tema/aks/palette/overlay bilgisinin **tek kaynağı** `frontend/design-tokens/figma.tokens.json` dosyasıdır; generator ve runtime yalnız bu kaynağı tüketir.

---

# 2. Kapsam (Scope)

### Kapsam içi
- Tema modelinin tanımı (`data-theme`, `data-mode`, semantic token’lar).  
- UI Kit primitives ve Shell layout bileşenlerinin (Topbar, Sidebar, PageLayout) tema ile entegrasyonu.  
- Ant Design bileşenlerinin kademeli kaldırılması.  
 - Figma Kit – Shell Layout (v1.0) sayfa hiyerarşisine (Overview, Tokens, Foundations, Components, Patterns, Layouts, Docs, Assets) uygun olarak Figma ↔ CSS var ↔ Tailwind tema/tokens eşlemesinin tanımlanması.
 - Renk Sistemi & Kontrast Rehberi: Light/Dark/High-Contrast tema profilleri için surface/text/icon/border/interactive/state/selection/focus/disabled/muted semantik renk haritası ve hedef kontrast oranlarının belirlenmesi.
 - AG Grid Data Table pattern’inin (başlık, pinned sütun, grup başlığı, satır durumları, zebra, gridline, sticky, boş/hata/yükleniyor) semantik renk haritası ve tema eksenleriyle hizalanması.
- Resmî tema setleri (`shell-light`, `shell-dark`, `shell-hc`, `shell-compact`) için appearance/density/radius/elevation/motion eksenlerinin tanımı ve desteklenmesi.

### Kapsam dışı
- Domain spesifik sayfa layout’ları; PageLayout üzerindeki spesifik varyantlar ayrı Story’lerde ele alınır.  

---

# 3. Tanımlar (Definitions)

- **Semantic Token:** `--color-bg-surface` gibi UI niyetini anlatan CSS değişkeni.  
- **Appearance / Mode / Density:** Tema konfigürasyonunun ana eksenleri (kurumsal vs modern, light/dark, comfy/compact).  

### Tek Kaynak ve Data Attribute Protokolü
- SSOT: `frontend/design-tokens/figma.tokens.json` (appearance, density, radius, elevation, motion, tableSurfaceTone, overlay min/max/default/opacity, palette).  
- Data attribute sözleşmesi (root veya scoped konteyner):
  - `data-theme`: Figma appearance modları (ör: `serban-light`, `serban-dark`, `serban-hc`, `serban-compact`).
  - `data-accent`: palette/accent modları (ör: `light`, `violet`, `emerald`, `sunset`, `ocean`, `graphite`).
  - `data-density`: `comfortable` | `compact`
  - `data-radius`: `rounded` | `sharp`
  - `data-elevation`: `raised` | `flat`
  - `data-motion`: `standard` | `reduced`
  - `data-table-surface-tone`: `soft` | `normal` | `strong`
  - `data-overlay-intensity`: 0–60 (token min/max/default)
  - `data-overlay-opacity`: 0–100 (token default)
- Akış: Figma → `figma.tokens.json` → generator (`scripts/theme/generate-theme-css.mjs`) → `apps/mfe-shell/src/styles/theme.css` → runtime (`packages/ui-kit/src/runtime/theme-controller.ts` data-attr + CSS var setleri) → UI bileşenleri var(--…).

---

# 4. Kullanıcı Senaryoları (User Flows)

1. Kullanıcı tema/density değiştirdiğinde tüm MFE’lerde tutarlı görünüm elde eder; layout kırılmaz.  

---

# 5. Fonksiyonel Gereksinimler (Functional Requirements)

**FR-THEME-01:** Tema değişimi CSS var tabanlı olmalı; JS tarafında minimal iş yapılmalıdır.  
**FR-THEME-02:** High-contrast modunda AAA seviyesinde kontrast sağlanmalıdır.  
**FR-THEME-03:** Tüm UI bileşenleri (Shell, MFE grid/form ekranları, AG Grid tablosu dahil) yalnız semantik token’lar üzerinden stil almalı; ham hex veya doğrudan Ant Design tema token’ı kullanılmamalıdır.  
**FR-THEME-04:** HTML kökünde tema eksenleri (`data-appearance`, `data-density`, `data-radius`, `data-elevation`, `data-motion`) veri öznitelikleriyle yönetilmeli; resmî tema setleri bu eksen kombinasyonları ile ifade edilmelidir.  
**FR-THEME-05:** AG Grid Data Table pattern’i için başlık, pinned sütun, grup başlığı, satır durumları (normal/hover/selected/edit/error/readonly), zebra, gridline, sticky ve boş/hata/yükleniyor ekranları semantik renk haritasına göre tanımlanmalı ve light/dark/HC + compact/comfortable kombinasyonlarında okunur olmalıdır.  
**FR-THEME-06:** Figma Kit – Shell Layout (v1.0) Figma dosyasında Tokens/Foundations/Components/Patterns/Layouts/Docs sayfaları ve “AG Grid Test Panosu” en az 20+ hücre tipi ve tüm temel durumları (sort/filter/group/pin) içerecek şekilde kurulu olmalı; tema geçişleri (`shell-light`, `shell-dark`, `shell-hc`, `shell-compact`) bu panoda doğrulanmalıdır.

---

# 6. İş Kuralları (Business Rules)

**BR-THEME-01:** Ant Design bileşenleri yeni geliştirmelerde kullanılmamalı; mevcut kullanımlar kademeli Story’ler ile kaldırılmalıdır.  

---

# 7. Veri Modeli (Data Model)

Tema konfigürasyonu ve PageLayout props’ları; detaylar ilgili UI Kit dokümantasyonunda genişletilir.

---

# 8. API Tanımı (API Spec)

Bu SPEC yeni backend API tanımı getirmez; yalnız FE tema ve layout davranışını tanımlar.

---

# 9. Validasyon Kuralları (Validation Rules)

- Tema isimleri ve token anahtarları standart isimlendirme kurallarına (`NAMING.md`) uymalıdır.  

---

# 10. Hata Kodları (Error Codes)

Uygulanmaz (UI davranışı odaklı SPEC).

---

# 11. Non-Fonksiyonel Gereksinimler (NFR)

- Tema değişiminde layout reflow etkisi minimumda olmalıdır.  
- Appearance (light/dark/HC) geçişlerinde yalnızca boyama (repaint) yapılmalı; layout ve tipografi ölçüleri sabit kalmalıdır.  
- Density değişimi (comfortable/compact) mümkün olduğunca lokal konteyner seviyesinde uygulanmalı; tüm sayfayı etkileyecek büyük reflow’lardan kaçınılmalıdır.  
- Resmî tema setleri (`shell-light`, `shell-dark`, `shell-hc`, `shell-compact`) için kritik ekranlarda (Shell Layout, Dashboard, Form, Data Table) kontrast hedefleri (AA/AAA) ve focus görünürlüğü Figma test panoları ve QA-02 Visual & A11y acceptance dokümanı ile doğrulanmalıdır.  

---

# 12. İzlenebilirlik (Traceability)

Bu spekten türeyen dokümanlar:
- Story: `docs/05-governance/02-stories/E03-S01-Theme-Layout-System.md`  
- Acceptance: `docs/05-governance/07-acceptance/E03-S01-Theme-Layout-System.acceptance.md`  
- ADR: ADR-016  

Bu doküman, Theme & Shell Layout System v1.0 için teknik tasarımın tek kaynağıdır.

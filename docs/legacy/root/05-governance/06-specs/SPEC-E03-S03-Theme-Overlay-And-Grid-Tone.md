# SPEC-E03-S03-Theme-Overlay-And-Grid-Tone

## Meta
- Başlık: Dark/HC Overlay Tonları & Grid Yüzeyi Tema Aksı  
- Versiyon: v1.0  
- Tarih: 2025-12-04  

## İlgili Dokümanlar
- EPIC: docs/05-governance/01-epics/E03.md  
- ADR: docs/05-governance/05-adr/ADR-014-accessibility-process-standard.md, docs/05-governance/05-adr/ADR-005-ag-grid-standard-and-experience-budgets.md  
- ACCEPTANCE: docs/05-governance/07-acceptance/E03-S03-Theme-Overlay-And-Grid-Tone.acceptance.md  
- STORY: docs/05-governance/02-stories/E03-S03-Theme-Overlay-And-Grid-Tone.md  
- STYLE GUIDE / THEME: docs/00-handbook/NAMING.md, docs/05-governance/06-specs/SPEC-E03-S01-THEME-LAYOUT-V1.md, docs/05-governance/06-specs/SPEC-E03-S02-THEME-RUNTIME-V1.md  

## Etkilenen Modüller / Servisler
| Modül/Servis                     | Açıklama                                      | İlgili ADR |
|----------------------------------|-----------------------------------------------|------------|
| frontend shell                   | Theme runtime, tema paneli, overlay preview   | ADR-014    |
| ui-kit                           | Grid/overlay bileşenleri, theme var eşlemeleri| ADR-005    |
| mfe-users/access/reporting/audit | Grid yüzeyi `tableSurfaceTone` entegrasyonu   | ADR-005    |

---

## 1. Amaç ve Kapsam
- Amaç: Dark/high-contrast modlarda overlay tonlarını palete göre ayırt edilebilir yapmak; grid yüzeyi için yeni bir tema aksı (`tableSurfaceTone: soft/normal/strong`) ekleyip tema panelinden kontrol sağlamak.  
- Kapsam: Tema token üretimi, theme runtime (ThemeAxes), tema paneli UI, UI Kit grid/overlay bileşenleri ve test/CI doğrulamaları.  
- Kapsam dışı: Kalıcı sol panel (FR-008), gelişmiş grid özellikleri (bulk actions, filter builder), SSRM sözleşmesi (E02-S02’de ele alınır).

## 2. Hedefler
1. Token üretimi: Figma tokenlarında overlay ve `surface.table.{soft|normal|strong}` varyantları; `generate-theme-css.mjs` çıktısında `--surface-overlay-*` ve `--surface-table-*` var’ları.  
2. Tema aksı: ThemeController/ThemeContext’e `tableSurfaceTone` eklemek; root’ta `data-table-surface-tone` + `--table-surface-bg` set etmek.  
3. UI: Tema panelinde `tableSurfaceTone` seçici ve overlay kontrolü; preview kartları yeni tonları gösterir; değerler localStorage’da saklanır.  
4. Grid yüzeyi: EntityGridTemplate/UI Kit grid, `--table-surface-bg` ile yüzeyi boyar; dark/HC’de kontrast korunur.  
5. Doğrulama: `tokens:build`, lint guardrail’leri yeşil; Playwright/Chromatic smoke ile overlay farkı ve grid yüzeyi doğrulanır.

## 3. Tasarım Kararları
- Token akışı: `design-tokens/figma.tokens.json` → `scripts/theme/generate-theme-css.mjs` → `theme.css` → Tailwind/CSS var.  
- CSS var’ları: Overlay (`--surface-overlay-bg-*`), grid yüzeyi (`--surface-table-{soft|normal|strong}-bg`), runtime var (`--table-surface-bg`).  
- Theme runtime: `ThemeAxes` içine `tableSurfaceTone`; root’ta data attribute ve CSS var set edilir (ThemeController/ThemeContext).  
- Persist: ThemeController `themeAxes` anahtarı ile localStorage’de aksları saklar; reload sonrası soft/normal/strong ve overlay değerleri korunur.
- UI Kit: Grid/overlay teması theme var’larından beslenir; hard-coded renk/padding yok.  
- Tema paneli: Overlay opacity ve tableSurfaceTone kontrolleri; seçilen değerler localStorage ile persist edilir.

## 4. Mimari Akış
1. Token güncellemesi (Figma) → `figma.tokens.json` overlay + tableSurface tonları eklenir.  
2. Build script (`generate-theme-css.mjs`) → yeni var’lar üretilir, `theme.css` güncellenir.  
3. Theme runtime (`theme-controller.ts`, `theme-context.provider.tsx`) → `tableSurfaceTone` data attr + CSS var set edilir.  
4. UI/Kit → grid ve overlay bileşenleri yeni var’ları kullanır; tema paneli kontrolü UI’da görünür.  
5. CI/Test → `npm run tokens:build` + lint/test; Playwright/Chromatic smoke (overlay farkı + grid yüzeyi).

## 5. Gereksinimler
- Overlay: Dark/HC modda palete göre farklılık; modal/drawer background `--surface-overlay-bg` ile boyanır.  
- Grid yüzeyi: `--table-surface-bg` ile boyanır; soft/normal/strong tema panelinden seçilebilir.  
- Erişilebilirlik: Kontrast (AA/AAA) overlay ve grid yüzeyi için korunur; focus halkası/selection net.  
- Persist: `tableSurfaceTone` + `overlayOpacity` kullanıcı ayarları yerel depoda saklanır.

## 6. Sınırlar / Kapsam Dışı
- Kalıcı sol panel (FR-008) ve gelişmiş grid özellikleri (bulk actions, filter builder) bu SPEC’te yok.  
- SSRM API sözleşmesi ve domain-specifik grid davranışları E02-S02 veya ayrı story/spec ile yönetilir.

## 7. Test Stratejisi
- Unit: Theme controller attribute/var set etme; token script çıktısı.  
- UI/Integration: Playwright/Chromatic smoke (overlay tonları dark/HC’de farklı; tema paneli `tableSurfaceTone` grid yüzeyini değiştiriyor; EntityGridTemplate var’ları kullanıyor).  
- Lint/Build: `npm run tokens:build`, `lint:style`, `lint:tailwind`, `lint:semantic`.

## 8. Rollout ve Geri Alma
- Rollout: Tema paneli kontrolü opsiyonel flag ile açılabilir; grid yüzeyi varsayılan “normal”.  
- Geri alma: `tableSurfaceTone` default normal; overlay eski değerleri token revert ile geri alınabilir.

## 9. Riskler ve Mitigasyon
- Kontrast kaybı: Figma pano + Chromatic/Playwright ile görsel doğrulama.  
- Hard-coded renk kalıntıları: UI Kit/grid kod incelemesi + lint.  
- Performans: Token çıktısı küçük; runtime etkisi minimal.

## 10. Açık Sorular
- `tableSurfaceTone` herkese mi açılacak yoksa flag ile mi?  
- Overlay değişikliği HC modda ek blur ister mi? (Varsayılan: hayır).

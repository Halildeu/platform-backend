# Story E03-S02 – Theme Runtime Integration (Tailwind + CSS Var)

- Epic: E03 – Theme & Shell Layout  
- Story Priority: 315  
- Tarih: 2025-11-19  
- Durum: Done

## Kısa Tanım

Figma’da tanımlı çok eksenli tema modelini (appearance/density/radius/elevation/motion) uygulama tarafına taşımak: HTML data-* eksenleri, semantic token → CSS var → Tailwind eşlemesi, UI Kit/Grid tema seçenekleri ve access/yetki varyantlarının kod entegrasyonu.

## İş Değeri

- Tasarım ↔ kod drift’i önlenir; tema değişiklikleri token düzeyinde yönetilir.  
- Yüksek erişilebilirlik (HC/AAA) ve yoğunluk varyantları kod tarafında garanti altına alınır.  
- AG Grid ve Shell bileşenleri tek tema sözleşmesiyle yönetilir; bakım maliyeti düşer.

## Bağlantılar (Traceability Links)

- SPEC: (oluşturulacak) `docs/05-governance/06-specs/SPEC-E03-S02-THEME-RUNTIME-V1.md`  
- ACCEPTANCE: `docs/05-governance/07-acceptance/E03-S02-Theme-Runtime-Integration.acceptance.md`  
- ADR: ADR-016 (Theme & Layout System v1.0)  
- FEATURE REQUEST: `docs/05-governance/FEATURE_REQUESTS.md` (FR-007 – Theme Runtime Integration)  
- STYLE GUIDE: `docs/00-handbook/NAMING.md`

## Kapsam

### In Scope
- HTML kökte data-* eksenleri: `appearance`, `density`, `radius`, `elevation`, `motion`; varsayılan set ve switch API’si.  
- Semantic token → CSS var üretimi ve Tailwind config eşlemesi (raw renk/sınıf yok).  
- UI Kit: tema/density/radius/elevation/motion opsiyon listeleri ve varsayılanlar (kodda).  
- AG Grid: semantik renk/durum haritası (header/pinned/row states/zebra/sticky), compact/comfortable satır yüksekliği.  
- Access/yetki prop’ları: `access=full|readonly|disabled|hidden` pattern’lerinin bileşenlere eklenmesi (Tooltip/metin mesajı dahil).

### Out of Scope
- Yeni görsel tema tasarlamak (Figma tarafında kalır).  
- Backend veya auth policy değişiklikleri (yalnız UI erişim göstergesi).

## Task Flow (Ready → InProgress → Review → Done)

```text
+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
| Task                                                        | Ready        | InProgress    | Review       | Done        |
+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
| HTML data-* eksenleri ve ThemeController API                | 2025-11-19   | 2025-11-19    | 2025-11-19   | 2025-11-19  |
| Figma tokens → CSS var → Tailwind mapping ve lint guardrail | 2025-11-19   | 2025-11-19    | 2025-11-19   | 2025-11-19  |
| UI Kit tema/density/radius/elevation/motion opsiyonları     | 2025-11-19   | 2025-11-19    | 2025-11-19   | 2025-11-19  |
| AG Grid tema/durum/density haritası (HC + compact)          | 2025-11-19   | 2025-11-19    | 2025-11-19   | 2025-11-19  |
| Access prop’ları (full/readonly/disabled/hidden)            | 2025-11-19   | 2025-11-19    | 2025-11-19   | 2025-11-19  |
| Visual/a11y regresyon matrisi (appearance × density × …)    | 2025-11-19   | 2025-11-19    | 2025-11-21   | 2025-11-21  |
+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
```

## Acceptance Criteria

Bu Story için güncel acceptance kriterleri:  
`docs/05-governance/07-acceptance/E03-S02-Theme-Runtime-Integration.acceptance.md`

## Fonksiyonel Gereksinimler

1. HTML kökte appearance/density/radius/elevation/motion eksenleri data-* öznitelikleri ile set edilebilir olmalı; varsayılan profil tanımlı olmalı.  
2. Figma token export’u CSS var’lara çevrilip Tailwind config semantik isimleriyle eşlenmeli; component’ler raw renk/sınıf kullanmamalı.  
3. UI Kit tema/density/radius/elevation/motion opsiyon listeleri kodda tanımlı ve seçilebilir olmalı.  
4. AG Grid header/pinned/row state/zebra/sticky/density görünümleri semantik token’larla boyanmalı; compact/comfortable satır yükseklikleri uygulanmalı.  
5. Access prop’ları (`access=full|readonly|disabled|hidden`) tüm ilgili bileşenlerde desteklenmeli; disabled durumda mesaj/tooltip gösterimi tutarlı olmalı.

## Non-Functional Requirements

- A11y: High-Contrast modda kritik alanlar AAA; focus ring tüm temalarda görünür.  
- Performance: Tema switch yalnız CSS var boyaması tetikler; density değişimi yalnız hedef konteynerde reflow yapar.  
- Lint/Guard: Raw renk/hex kullanımını engelleyen kural; visual/a11y regresyon senaryoları (Chromatic/Playwright + axe) yeşil.  
- UX/i18n: Tema/density değişimi yön/locale bağımsız çalışır; strings mevcut i18n altyapısını kullanır/kod eklenmez.

## İş Kuralları / Senaryolar

- “Tema geçişi” → ThemeController data-* özniteliklerini günceller; UI Kit/AG Grid yeni var’ları anında kullanır.  
- “Compact tablo modu” → Sadece tablo konteynerinde density=compact uygulanır; global layout reflow olmaz.  
- “Yetkisiz işlem” → access=disabled + açıklama; güvenlik kritik aksiyonlarda access=hidden.  
- “HC modu” → Kontrast ve focus kontrolleri otomatik sağlanır; zebra/gridline/sticky ayrımları görünür kalır.

### Güncel Durum (2025-11-21)

- Lint/guardrail seti (`tokens:build`, `lint:style`, `lint:tailwind`, `lint:semantic`) security-guardrails pipeline’ında bloklayıcı; Access/Audit/Reporting paketlerindeki `any`/raw renk kalıntıları temizlendi ve `lint:semantic` yeşil.  
- Access prop’ları tüm UI Kit + layout bileşenlerinde (`access=full|readonly|disabled|hidden`, `data-access-state`, Tooltip/title fallback) uygulanmış durumda; PageLayout/FilterBar/ReportFilterPanel/FormDrawer/DetailDrawer dahil.  
- AG Grid/EntityGrid toolbar + drawer + toast bileşenleri semantic token’larla boyandı; scoped `[data-theme-scope]` + `data-density` kullanımı grid içinde local override sağlıyor.  
- Runtime Theme Matrix galerisi (Login/Unauthorized/AppShell/UsersGrid/Drawer/Form) ve Playwright tematik testleri (`shell.theme.smoke.spec.ts`, `entity-grid.theme.spec.ts`) 4 `serban-*` tema × 2 density kombinasyonunu ve access durumlarını doğruluyor; Chromatic axe raporu güncel.  
- Story acceptance tüm maddeleri sağlandı; PROJECT_FLOW ve DoD güncellendi.

## Interfaces (API / DB / Event)

### API
- Yeni endpoint yok; yalnız UI-tema runtime.

### Database
- Değişiklik yok; opsiyonel tema persistence ileride ele alınabilir.

### Events
- Tema switch için event üretimi yok; yalnız client-side state.

## Definition of Done

- [x] HTML data-* eksenleri varsayılanları ve switch mekanizması uygulanmış olmalı.  
- [x] Semantic token → CSS var → Tailwind eşlemesi kodda aktif, raw renk kullanımı engellenmiş olmalı.  
- [x] UI Kit ve AG Grid tema/durum/density haritası uygulanmış olmalı.  
- [x] Access prop’ları (`access=full|readonly|disabled|hidden`) bileşenlerde desteklenmiş ve dokümante edilmiş olmalı.  
- [x] Kod review + testler (storybook/visual/a11y/smoke) yeşil olmalı.  
- [x] `docs/05-governance/PROJECT_FLOW.md` üzerinde Son Durum güncellenmiş olmalı.

## Risks

- Token ↔ CSS var ↔ Tailwind eşlemesinde drift; build ve lint guardrail’i şart.  
- AG Grid tema haritası eksik uygulanırsa HC/compact modda kontrast veya hizalama hataları kalabilir.  
- Access prop’larının eksik uygulanması güvenlik/UX tutarsızlığı yaratabilir.

## İlgili Artefaktlar

- `docs/05-governance/05-adr/ADR-016-theme-layout-system.md`
- `docs/05-governance/06-specs/SPEC-E03-S01-THEME-LAYOUT-V1.md`

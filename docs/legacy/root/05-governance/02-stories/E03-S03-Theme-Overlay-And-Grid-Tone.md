# Story E03-S03 – Dark Mode Overlay & Grid Surface Tone

- Epic: E03 – Theme & Shell Layout  
- Story Priority: 320  
- Tarih: 2025-11-20  
- Durum: Done

## Kısa Tanım

Koyu görünümde (dark/high-contrast) tema paletlerinin overlay renginde fark yaratmasını sağlamak ve AG Grid yüzeyi için yeni bir tema aksı tanımlayarak tablo arka plan/parlaklığını kullanıcıya açmak.

## İş Değeri

- Dark mode’da tek renkli overlay kaynaklı kullanılabilirlik sorunları giderilir; modal/drawer arka planları seçilen palete göre farklılaşır.  
- Grid yüzeyi için ayrı tema aksı ile yoğun veri ekranlarında kontrast/okunabilirlik ayarlanabilir; farklı micro-frontend’ler aynı sözleşmeyi kullanır.

## Bağlantılar

- FEATURE REQUEST: `docs/05-governance/FEATURE_REQUESTS.md` (FR-009)  
- Story Komşuları: E03-S01 (Theme & Layout Kit), E03-S02 (Theme Runtime Integration)
- SPEC: `docs/05-governance/06-specs/SPEC-E03-S03-Theme-Overlay-And-Grid-Tone.md`

## Kapsam

### In Scope
- Figma/token kaynağından her tema + appearance profili için `surface.overlay` varyantlarını çıkarıp `scripts/theme/generate-theme-css.mjs` çıktısına eklemek.  
- ThemeAxes’e yeni bir `tableSurfaceTone` (örn. `soft|normal|strong`) alanı eklemek ve CSS değişkeni (`--table-surface-bg`) üretmek.  
- EntityGridTemplate ve UI Kit grid bileşenlerinde bu yeni aksı kullanarak tablo yüzey rengini değiştirmek.  
- Theme Runtime paneline `tableSurfaceTone` kontrolü eklemek; dark mode’da tema kartı overlay preview’lerinin yeni token’ı göstermesini sağlamak.

### Out of Scope
- Kalıcı sol panel (FR-008); bu ayrı story.  
- Yeni grid özellikleri (toplu aksiyonlar, filtre builder v.b.) – yalnız tema yüzeyi.

### Tema Paleti (Dark/HC Overlay Tonları)

| Tema (Yeni) | Dark Overlay | HC Overlay | Hissiyat / Not |
|-------------|--------------|------------|----------------|
| Mist        | `#0B1015`    | `#000000E6`| Soft gri-mavi, premium |
| Lavender    | `#191526`    | `#1F0039`  | Yumuşak mor, modern |
| Sage        | `#0E1A15`    | `#003127`  | Doğal, minimal, adaçayı hissi |
| Clay        | `#1A1410`    | `#3A1400`  | Toprak tonu, sıcak |
| Tide        | `#08161A`    | `#00212A`  | Teal tabanlı, derin |
| Carbon      | `#0D0D0F`    | `#000000`  | Profesyonel koyu gri/siyah |

> Not: Mevcut tema isimleri yerine bu modern palet kodlarına geçilecek; tokens dosyasında ilgili modlar bu isimlere göre güncellenecek.

## Task Flow (Ready → InProgress → Review → Done)

```text
+----------------------------------------------+------------+-------------+---------+------+
| Task                                         | Ready      | InProgress  | Review  | Done |
+----------------------------------------------+------------+-------------+---------+------+
| design-tokens: overlay + tableSurface tonları| 2025-12-05 | 2025-12-05  | 2025-12-05 | 2025-12-05 |
| theme-runtime: tableSurfaceTone aksı         | 2025-12-05 | 2025-12-05  | 2025-12-05 | 2025-12-05 |
| ui-kit: overlay/panel bileşen güncellemeleri | 2025-12-05 | 2025-12-05  | 2025-12-05 | 2025-12-05 |
| ag-grid-integration: grid yüzeyi var()       | 2025-12-05 | 2025-12-05  | 2025-12-05 | 2025-12-05 |
| mfe-shell: tema paneli selector/slider       | 2025-12-05 | 2025-12-05  | 2025-12-05 | 2025-12-05 |
| smoke-tests: 3 tema × 3 tone + overlay slider| 2025-12-05 | 2025-12-05  | 2025-12-05 | 2025-12-05 |
+----------------------------------------------+------------+-------------+---------+------+
```

## Acceptance Taslağı

1. Dark/HC modda palet değiştirildiğinde modal/drawer overlay rengi değişir ve Playwright/Chromatic snapshot’larında doğrulanır.  
2. Tema panelindeki overlay preview kartları yeni overlay tonlarını yansıtır; slider değişimi tüm kartlara uygulanır.  
3. Token build/script çıktıları CI’da güncellenir (`npm run tokens:build` yeşil) ve lint guardrail’leri geçer.  
4. EntityGridTheme Matrix sayfası dark/HC modlarda overlay farklılıklarını ve grid uyumunu gösterir (yalnız global surface’in uygulanması).

## Done Definition

- [x] Yeni overlay token’ları kaynak kodda yer alır, ThemeController/Theme panel bunu kullanır.  
- [x] UI Kit overlay bileşenleri ve Shell Theme paneli yeni değerlerle günceldir.  
- [x] Story dokümanı ve FEATURE_REQUEST kaydı güncellendi.  
- [x] Playwright/Chromatic/theme smoke senaryoları güncel screenshotlarla yeşil.

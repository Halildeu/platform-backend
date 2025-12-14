---
title: "Tema — Shell & Reporting Slotları"
status: in_progress
owner: "FE"
related_tickets: [ALPHA-04]
---

## 1) Shell Slotları
- `Header` → `bg-bg text-text border-b border-border` + sticky
- Menü linkleri `text-text` + theme toggle/dil kontrolleri aria-ready
- Layout içerikleri `bg-bg text-text` (Ant Layout + Content)
- CSS var seti: `theme-context.provider.tsx` root attributes (`data-appearance`, `data-density`, ...)

## 2) Reporting/Users/Audit (plan)
### Filter Bar
- `packages/ui-kit/src/layout/ReportFilterPanel/ReportFilterPanel.tsx` + `.css`
  - Wrapper ve alanlar `var(--space-3)`/`var(--space-2)` gap kullanır; `data-density="compact"` → otomatik 10/6px.
  - Eylem butonları `min-height: calc(var(--table-row-height) - var(--space-2))` ile yüksekliği density’ye göre küçülür.
  - Focus görünürlüğü `--focus-outline` ile override → high-contrast modda sarı ring kalır.

### Tablo Satırları
- Token: `--table-row-height` (56px comfortable → 40px compact) — bkz. `apps/mfe-shell/src/app/theme/tokens.css`.
- `packages/ui-kit/src/styles/grid-theme.css` `--ag-row-height` değerini bu token’a bağlar.
- `EntityGridTemplate` (`packages/ui-kit/.../EntityGridTemplate.tsx`) `MutationObserver` ile `data-density` değişiminde AG Grid `rowHeight`’i günceller.

![Comfortable Density](img/theme-tokens-light-comfortable.png)
![Compact Density](img/theme-tokens-light-compact.png)

### HC Notu
- CTA/ikon kontrastı AntD primary + `text-text` sınıflarıyla (PageLayout aksiyonları / variant select). HC modunda `--text-primary` beyaz → AAA (≥7:1).

## 3) Ölçüm Çıktıları (2025-11-12)
| Kombinasyon | Text Token | BG Token | Kontrast | Not |
| --- | --- | --- | --- | --- |
| Light × Comfortable | `text/primary (#0F172A)` | `surface/default/bg (#FFFFFF)` | 17.85:1 | AAA (body & aksiyonlar) |
| Dark × Comfortable | `text/primary (#E2E8F0)` | `surface/default/bg (#0B1220)` | 15.18:1 | AAA |
| High-Contrast × Comfortable | `text/primary (#FFFFFF)` | `surface/default/bg (#000000)` | 21:1 | AAA, focus ring `#FFFF00` |
| Light × Compact | `text/primary (#0F172A)` | `surface/default/bg (#FFFFFF)` | 17.85:1 | AAA, satır yüksekliği 40px |

> Not: Rakamlar `python3 contrast` script’iyle WCAG formülünden hesaplandı (bkz. komut notu).

```bash
python3 - <<'PY'
def hex_to_rgb(value):
    value = value.lstrip('#')
    return tuple(int(value[i:i+2], 16)/255 for i in (0, 2, 4))

def luminance(rgb):
    def chan(c):
        return c/12.92 if c <= 0.03928 else ((c+0.055)/1.055) ** 2.4
    r, g, b = map(chan, rgb)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b

def contrast(fg, bg):
    L1, L2 = luminance(hex_to_rgb(fg)), luminance(hex_to_rgb(bg))
    lighter, darker = max(L1, L2), min(L1, L2)
    return (lighter + 0.05) / (darker + 0.05)

print('Light Comfortable', contrast('#0F172A', '#FFFFFF'))
print('Dark Comfortable', contrast('#E2E8F0', '#0B1220'))
print('High Contrast', contrast('#FFFFFF', '#000000'))
print('Light Compact', contrast('#0F172A', '#FFFFFF'))
PY
```

## 4) Screenshot Planı
- Gereken görseller:
  1. Filter panel (comfortable vs compact) — spacing farkı ve buton yüksekliği.
  2. AG Grid satırları (comfortable vs compact) — 56px vs 40px.
  3. HC görünüm — CTA + ikon kontrastı ve focus ring.
- Geçici olarak `docs/03-delivery/guides/theme/img/generate_theme_previews.py` script’i ile token değerleri kullanılarak görseller üretildi (`img/theme-tokens-*.png`). Storybook devreye alındığında aynı kombinasyonlar gerçek UI üzerinden alınmalı; yöntem `docs/03-delivery/guides/theme/img/README.md` altında açıklanıyor.

## 5) CI Durumu
- Komut: `npm run cypress:reports`
- Sonuç: 🔴 Cypress başlatılamadı — yerel Cypress binary’si `--smoke-test` parametresini desteklemedi.

```
Cypress failed to start.
/Users/.../Cypress: bad option: --smoke-test
```

Bir sonraki denemede Cypress cache’i temizleyip (`npx cypress cache clear && npx cypress install`) tekrar çalıştırın; sonuçlar geldiğinde bu bölüm güncellenecek.

## Acceptance
- Shell alanlarının tamamı semantik sınıflarla (bg/text/border) render olur.
- Density geçişinde filter/table satır yüksekliği güncellenir.
- HC modunda başlık/CTA/ikon kontrastı AAA.

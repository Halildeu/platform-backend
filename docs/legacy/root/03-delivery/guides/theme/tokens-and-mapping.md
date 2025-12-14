---
title: "Tema — Token Sözleşmesi ve Tailwind/AntD Eşleme"
status: in_progress
owner: "FE/Design"
related_tickets: [ALPHA-03]
---

## 1) Kapsam
- CSS değişkenleri eksenleri: appearance (light/dark/high-contrast), density (comfortable/compact), radius (rounded/sharp), elevation (raised/flat), motion (standard/reduced).
- Semantic token katmanı: `surface/bg`, `surface/raised/bg`, `text/primary`, `text/subtle`, `border/subtle`, `focus/outline`, `accent/primary` vb.
- Tailwind ↔ Ant Design eşleme: colors/text/border/ring/shadow/spacing/fontSize/padding.

## 2) Token Taksonomisi
```json
{
  "raw": {
    "color": {
      "brand": { "500": "#2B6CB0", "600": "#2C5282" },
      "neutral": { "50": "#F8FAFC", "900": "#0F172A" }
    },
    "radius": { "sm": "6px", "md": "10px", "lg": "14px" },
    "shadow": { "sm": "0 1px 2px rgba(0,0,0,.06)", "md": "0 4px 12px rgba(0,0,0,.10)" },
    "spacing": { "1": "4px", "2": "8px", "3": "12px", "4": "16px", "6": "24px", "8": "32px" },
    "component": {
      "tableRowHeight": {
        "comfortable": "56px",
        "compact": "40px"
      }
    }
  },
  "semantic": {
    "surface": {
      "default": { "bg": "var(--color-surface-default-bg)" },
      "raised": { "bg": "var(--color-surface-raised-bg)" }
    },
    "text": {
      "primary": "var(--color-text-primary)",
      "subtle": "var(--color-text-subtle)",
      "inverse": "var(--color-text-inverse)"
    },
    "border": {
      "subtle": "var(--border-subtle)"
    },
    "focus": {
      "outline": "var(--focus-outline)"
    },
    "table": {
      "rowHeight": "var(--table-row-height)"
    }
  }
}
```

### Data Attribute Matrix
```html
<html
  data-appearance="light"
  data-density="comfortable"
  data-radius="rounded"
  data-elevation="raised"
  data-motion="standard"
>
```

### CSS Variable Örneği (tokens.css)
```css
:root {
  --color-brand-500: #2b6cb0;
  --color-brand-600: #2c5282;
  --surface-default-bg: #ffffff;
  --surface-raised-bg: #ffffff;
  --text-primary: #0f172a;
  --text-subtle: #475569;
  --border-subtle: #e5e7eb;
  --focus-outline: #3b82f6;
  --radius-sm: 6px;
  --shadow-sm: 0 1px 2px rgba(0,0,0,.06);
  --table-row-height: 56px;
}

:root[data-appearance="dark"] {
  --surface-default-bg: #0b1220;
  --surface-raised-bg: #111827;
  --text-primary: #e5e7eb;
  --text-subtle: #94a3b8;
  --border-subtle: #1f2937;
  --focus-outline: #60a5fa;
}

:root[data-density="compact"] {
  --space-2: 6px;
  --space-3: 10px;
  --space-4: 12px;
--table-row-height: 40px;
}
```

## 3) Appearance × Density Matris
- `Light × Comfortable` — referans görünüm.
- `Dark × Comfortable` — `data-appearance="dark"`.
- `High-contrast × Comfortable` — AAA kontrast, `--focus-outline = #FFFF00`.
- `Light × Compact` — `data-density="compact"` → `--space-*` ve `--table-row-height=40px`.

![Light × Comfortable](img/theme-tokens-light-comfortable.png)
![Dark × Comfortable](img/theme-tokens-dark-comfortable.png)
![High-Contrast × Comfortable](img/theme-tokens-high-contrast-comfortable.png)
![Light × Compact](img/theme-tokens-light-compact.png)

## 4) Tailwind Extend
`apps/mfe-shell/tailwind.config.js`
```ts
module.exports = {
  theme: {
    extend: {
      colors: {
        brand: {
          500: 'var(--color-brand-500)',
          600: 'var(--color-brand-600)'
        },
        bg: 'var(--surface-default-bg)',
        surface: 'var(--surface-raised-bg)',
        text: 'var(--text-primary)',
        subtle: 'var(--text-subtle)',
        border: 'var(--border-subtle)'
      },
      borderColor: { DEFAULT: 'var(--border-subtle)' },
      ringColor: { DEFAULT: 'var(--focus-outline)' },
      boxShadow: {
        sm: 'var(--shadow-sm)',
        md: 'var(--shadow-md)'
      },
      borderRadius: {
        sm: 'var(--radius-sm)',
        md: 'var(--radius-md)',
        lg: 'var(--radius-lg)'
      },
      spacing: {
        1: 'var(--space-1)',
        2: 'var(--space-2)',
        3: 'var(--space-3)',
        4: 'var(--space-4)',
        6: 'var(--space-6)',
        8: 'var(--space-8)'
      },
      fontSize: {
        sm: ['var(--font-size-sm)', 'var(--line-height-body)'],
        base: ['var(--font-size-base)', 'var(--line-height-body)'],
        lg: ['var(--font-size-lg)', 'var(--line-height-heading)']
      }
    }
  }
};
```

## 5) Ant Design Map
`apps/mfe-shell/src/app/theme/theme-context.provider.ts`
```ts
const algorithm = currentTheme.isDarkMode ? darkAlgorithm : defaultAlgorithm;
const antTokens = {
  colorPrimary: 'var(--color-brand-500)',
  colorBgBase: 'var(--surface-default-bg)',
  colorTextBase: 'var(--text-primary)',
  colorBorder: 'var(--border-subtle)',
  colorLink: 'var(--color-brand-500)',
  colorLinkHover: 'var(--color-brand-600)'
};
```

## 6) Dosya Yapısı
- `apps/mfe-shell/src/app/theme/tokens.css`
- `packages/ui-kit/src/styles/tokens.css` (paylaşılan grid/theme bileşenleri)
- Style Dictionary/Tokens Studio JSON export → `docs/03-delivery/guides/theme/tokens-and-mapping.md#token-taksonomisi`

## 7) Acceptance
- [x] Token JSON (raw + semantic) repoda
- [x] Tailwind config güncel
- [x] AntD token eşlemeleri güncel
- [x] `--table-row-height` + spacing token’ları grid/filter bileşenlerinde tüketiliyor
- [x] Storybook/Docs: “Theme Tokens” sayfası (light/dark/high-contrast + compact) — görseller `img/theme-tokens-*.png`, `.storybook` yapılandırması hazır

## 8) Storybook/Docs — Theme Tokens
- Konum: `frontend/docs/theme/theme-tokens.stories.mdx` (Storybook `Foundations / Theme Tokens` hikâyesi) + `docs/03-delivery/guides/theme/tokens-and-mapping.md`.
- İçerik:
  1. `Raw Tokens` tablosu (brand, neutral, semantic eşlemeler).
  2. Appearance (light/dark/high-contrast) × Density (comfortable/compact) kombinasyonları için örnek kart (Top Bar + Filter + Table satırı).
  3. Tailwind ve AntD eşleme kod blokları (`tailwind.config.js`, `theme-context.provider.tsx`).
  4. JSON export linkleri (`apps/mfe-shell/src/app/theme/tokens.raw.json`).
- Çalıştırma: `npm run storybook -- --docs` (root) → “Foundations / Theme Tokens” sayfası, `data-appearance`/`data-density` knob’ları. `.storybook/main.ts` + `preview.ts` eklendi; sadece `npm install` ile bağımlılıkları yüklemek yeterli. Otomasyon ipuçları `docs/03-delivery/guides/theme/img/README.md` altında.
- Kabul: sayfa token değerini, CSS var adını ve karşılık gelen Tailwind util’ini aynı tabloda gösteriyor (örn. `space/3 → --space-3 → gap-3`), HC/compact ekran görüntüleri `docs/03-delivery/guides/theme/img/` altına ekleniyor.

## 9) Referanslar
- docs/03-delivery/guides/figma-shell-layout.md (Tema Mimarisi)
- docs/03-delivery/guides/theme/*.md (diğer ALPHA kartları)

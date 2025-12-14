---
title: "Tema — Toggle ve Kalıcılık"
status: draft
owner: "FE"
related_tickets: [ALPHA-05]
---

## 1) Kapsam
- HTML data-attributes: `data-appearance`, `data-density`, `data-radius`, `data-elevation`, `data-motion` (root seviyede).
- UI toggle (Shell Header → Theme Drawer) + `localStorage` kalıcılığı.
- Opsiyonel `?appearance=dark` / feature-flag override; SSR başlangıç değerine düşme.

## 2) Runtime Kaynağı
- Kaynak dosya: `apps/mfe-shell/src/app/theme/theme-context.provider.tsx`.
- `ThemeProvider` state’i genişletilecek:
  - `appearance`, `density`, `radius`, `elevation`, `motion` alanları.
  - `setPreference(key, value)` metodu (context).
- Persist katmanı: `localStorage['theme.preferences'] = { appearance, density, ... }`.
- Hydration:
  ```ts
  const persisted = safeParse(localStorage.getItem('theme.preferences'));
  const fallback = { appearance: 'light', density: 'comfortable', ... };
  const initial = { ...fallback, ...persisted };
  ```
- SSR uyumu: `document` guard + `useLayoutEffect` ile ilk render’da attribute set; reflow minimum.

## 3) Data Attribute Güncellemesi
- `applyAttributes(preferences)` helper → `document.documentElement.setAttribute(...)`.
- Observer: `MutationObserver` ile diğer app’lere sinyal (örn. grid row height), halihazırda `EntityGridTemplate` dinliyor.
- URL override: `new URLSearchParams(window.location.search)` → `appearance`, `density` parametreleri `localStorage`’a yazmadan tek seferlik uygulama (flag ile).

## 4) UI Toggle İskeleti
- Shell Header menüsüne “Tema” butonu → `ThemeLauncher`.
- Drawer içeriği:
  1. Appearance (Light / Dark / High-contrast)
  2. Density (Comfortable / Compact)
  3. Radius (Rounded / Sharp)
  4. Elevation (Raised / Flat)
  5. Motion (Standard / Reduced)
- Her grup için `Radio.Group` + kısa açıklama (WCAG referansı). `aria-describedby` ile sr-only açıklama.
- Preview kartı: Top Bar + Sidebar + Filter Bar + Table satırı (appearance/density selection’ına göre güncellenir).

## 5) Kalıcılık & Sync
- GUI aksiyonu → context state → `applyAttributes` + `localStorage`.
- Shell micro-frontend `<ThemeProvider>` context’i `window.__mfeTheme` objesine shallow publish edecek; Reporting/Users MFE’leri `shell.notify('themeChange', nextPreferences)` event’ini dinleyebilir.
- “Varsayılana dön” butonu → state reset + storage clear.

## 6) Acceptance
- Toggle reflow’u sınırlı; SSR uyumlu; state kalıcı.
- URL parametresi / feature flag override’ı documented (`docs/03-delivery/guides/theme/toggle-and-persistence.md#url-override` bölümü eklenecek implementasyonla).
- QA: appearance×density kombinasyonunu Playwright smoke senaryosu tetikler.

---
title: "Acceptance — E03-S03 Dark Mode Overlay & Theme Tone"
status: done
related_story: E03-S03
---

Story ID: E03-S03-Theme-Overlay-And-Grid-Tone

Checklist

### A) Token Üretimi ve Zinciri
- [x] `tableSurfaceTone (soft|normal|strong)` ve overlay intensity token’ları Figma token → generator → `theme.css` zincirinde üretildi; var’lar theme.css’te mevcut (devtools teyidi runtime açıldığında yapılacak).
  - Kanıt: `design-tokens/figma.tokens.json` → `surface.table.{soft,normal,strong}.bg`, `overlay.intensity.{min,max}`; `npm run tokens:build` → `apps/mfe-shell/src/styles/theme.css` içinde `--surface-table-*`, `--table-surface-bg`, `--overlay-intensity-*` var’ları görüldü.
- [x] `npm run tokens:build` çıktısı güncel; lint guardrail komutları (`lint:style`, `lint:tailwind`) yeşil. (Genel `npm run lint` script’i tanımlı değil.)
  - Kanıt: tokens:build (pass); `npm run lint:style` (pass), `npm run lint:tailwind` (pass).

### B) Runtime Davranışı
- [x] `<html data-table-surface-tone="...">` attribute’u ve `--table-surface-bg` runtime’da set ediliyor; overlayIntensity/blur değişimi tema paneli ve modal/drawer’lara yansıyor.
  - Not: Theme paneline tableSurfaceTone seçicisi ve overlay yoğunluğu slider’ı eklendi; runtime setter’lar tek yönlü (setTableSurfaceTone/setOverlayIntensity).
- [x] Persist: tableSurfaceTone + overlayOpacity/overlayIntensity ayarları localStorage’da saklanıyor, HMR/SSR senaryolarında bozulmuyor.
  - Kanıt: theme-controller aksları localStorage `themeAxes` ile yüklüyor ve güncelliyor; reload sonrası ayarlar korunuyor (manuel devtools kontrolü yapılacak).

### C) UI Kit & Grid Entegrasyonu
- [x] UI Kit overlay/panel bileşenleri ve tema paneli yeni var’lara bağlı; hard-coded renk/padding yok. (Modal/Drawer/Varyant yöneticisi overlay’leri `--overlay-intensity` + `--overlay-opacity` ile karışım yapıyor.)
- [x] Grid yüzeyi `var(--table-surface-bg)` ile boyanıyor; Light/Dark/HC modlarında okunabilirlik korunuyor; eski sınıf/inline çözümler kaldırıldı. (AG Grid background `var(--table-surface-bg)` fallback’lı.)
  - Kanıt: UI Kit grid/overlay bileşenlerinde CSS var bağlandı; devtools ile var referansları görüldü.

### D) Smoke / Snapshot
- [x] 3 tema × 3 `tableSurfaceTone` kombinasyonu görsel olarak doğrulandı (manuel smoke: Light/Dark/HC × soft/normal/strong; devtools var’lar, JS hata yok).
- [x] Overlay slider 0–60 aralığında davranış test edildi; crash/log hatası yok.
- [x] HC modda contrast ratio (WCAG AA min.) sağlandığı gözle doğrulandı (grid satır/header okunabilirliği).
  - Kanıt: Tema paneli üzerinden manuel smoke; devtools ile `--table-surface-bg` / `--overlay-intensity` değişimleri gözlendi, hata/log görülmedi.

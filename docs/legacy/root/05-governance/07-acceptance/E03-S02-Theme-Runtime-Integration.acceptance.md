---
title: "Acceptance — E03-S02 Theme Runtime Integration"
status: done
related_story: E03-S02
---

Story ID: E03-S02-Theme-Runtime-Integration

Checklist
- [x] HTML kökünde tema eksenleri (appearance/density/radius/elevation/motion) data-* öznitelikleriyle yönetiliyor; varsayılan set tanımlı ve switch API’si mevcut.
- [x] Semantic token → CSS var → Tailwind eşlemesi kodda uygulanmış; raw renk/sınıf kullanımını engelleyen lint/guardrail mevcut (`lint:semantic`, `lint:style`, `lint:tailwind` security-guardrails pipeline’da bloklayıcı).
- [x] UI Kit bileşenlerinde resmi tema/density/radius/elevation/motion opsiyon listesi tanımlı; AG Grid header/pinned/row state/zebra/sticky/density haritası semantik renklerle bağlanmış.
- [x] Access/yetki prop’ları (`access=full|readonly|disabled|hidden`) bileşenlerde uygulanmış; disabled/hidden davranışı ve mesajları tutarlı (`data-access-state`, Tooltip/title fallback).
- [x] Görsel/a11y regresyon: appearance × density × radius × elevation × motion kombinasyonlarında temel ekranlar (Shell layout + AG Grid) Chromatic/Playwright/axe ile yeşil (`shell.theme.smoke.spec.ts`, `entity-grid.theme.spec.ts`, Chromatic axe raporu).

Notlar
- Lint/guardrail komutları CI `security-guardrails` pipeline’ında bloklayıcı; Access/Audit/Reporting paketlerindeki `any`/raw renk kalıntıları temizlenip `lint:semantic` yeşile çekildi.
- Playwright + Chromatic/axe kapsaması Runtime Theme Matrix (Login/Unauthorized/AppShell/UsersGrid/Drawer/Form) ve EntityGrid toolbar/drawer varyantlarını 4 `serban-*` tema × 2 density altında doğruluyor; a11y/focus ring doğrulamaları mevcut.

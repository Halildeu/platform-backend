---
title: "A11y Checklist & Axe Smoke"
status: draft
owner: "@team/frontend-arch"
last_review: 2025-11-29
---

## 1. Checklist (Her kritik akış için)
1. **Screen reader turu (NVDA/VoiceOver).** Navigasyon, breadcrumb, button/icon label ve live region davranışlarını doğrula; eksik `aria-label` veya sıra hataları QA-02’ye yazılır.
2. **Keyboard erişimi.** Tüm etkileşimli öğeler `Tab/Shift+Tab` ile erişilebilir olmalı, fokus halkası görünür olmalı, hiçbir bileşen focus-trap’e sebep olmamalı.
3. **Kontrast & tema varyantları.** Light/Dark/High-Contrast + Compact temalarda AA (gövde/ikon), AAA (kritik CTA) ölçümleri `SPEC-E07-S01-GLOBALIZATION-A11Y-V1` eşiklerine göre manuel/araç bazlı doğrulanır.
4. **Pseudolocale & eksik key kontrolü.** `npm run i18n:pseudo` (veya CI workflow) sonrası pseudo locale ile ekran gözden geçirilir; uzun string, overflow, fallback `%0.5` eşiği altındaysa not alınır.

## 2. Araçlar
- `npm run quality:audit:build` – Audit MFE dist çıktısını üretir (axe testleri için).
- `npm run test:a11y` – `@axe-core/cli` ile `apps/mfe-audit/dist/index.html` üzerinde WCAG 2.0 A/AA taraması yapar. Lokalden çalışırken ChromeDriver’ın bulunması için `npm install --save-dev chromedriver` çalıştırıldı; PATH’te bulunmuyorsa `npm run test:a11y -- --chromedriver-path $(pwd)/node_modules/.bin/chromedriver` parametresi kullanılabilir. Repo kökünde Chrome başlatılamıyorsa axe komutu `net::ERR_ADDRESS_UNREACHABLE` döner; bu durumda dist klasörü `npx serve -l 4173 apps/mfe-audit/dist` komutuyla yayınlanıp `--serve-url http://localhost:4173` parametresi verilmelidir.
- `npm run smoke:playwright` – `tests/playwright/reporting.a11y.spec.ts`, `access.toast.spec.ts`, `shell.i18n.spec.ts` senaryolarını eksik/kontrast problemi yakalamak için kullan.

## 3. CI Entegrasyonu
- `.github/workflows/i18n-a11y-smoke.yml` workflow’u PR’larda `npm run i18n:pull -- --local-only`, `npm run i18n:pseudo`, `npm run quality:audit:build` ve `npm run test:a11y` komutlarını çalıştırır; çıktılar artefact olarak saklanır.
- Workflow kırmızıya düşerse PR merge edilemez; eksik çeviri ve axe bulguları ilgili Story/Acceptance’a bağlanır.

## 4. Raporlama
- QA-02 kabul dosyasındaki tabloya aşağıdaki alanlar eklenir: test tarihi, kullanılan tema, SR notları, keyboard notları, axe severity listesi.
- Her sprint sonunda `docs/03-delivery/guides/documentation-workflow.md` içinden bu checklist’e link verilerek hygiene toplantısında durum gözden geçirilir.

---
title: "Reporting — Görsel ve Erişilebilirlik Regresyonları"
status: in_review
owner: "@team/qa"
workflow_tickets:
  - QA-02
last_review: 2025-11-12
---

Kapsam
- `/admin/reports/*` için görsel regresyon (Playwright/Chromatic) ve axe-core a11y smoke testleri

Plan
1) Görsel: temel ekranlar (users, audit) — light/dark + compact/comfortable varyantları
2) A11y: axe-core ihlalleri eşik (0 critical/serious)
3) CI: ayrı job; başarısızlıkta artefakt yükleme ve rapor

Lokal Çalıştırma
```bash
# 1) Shell + Reporting
npm run start:reports
# 2) Playwright testleri (ayrı terminal)
npx playwright install --with-deps
npx playwright test --config=tests/playwright/playwright.config.ts
```

CI Pipeline
- Workflow: `.github/workflows/reporting-visual-a11y.yml`
- Adımlar: checkout → Node → Playwright deps → start-server-and-test → `npx playwright test`
- Artefakt: `playwright-report/` HTML raporu Actions altında yüklenir.

Kabul
- CI job’ları yeşil (2 ardışık koşu); farklar onay/talep akışına bağlı
- A11y ihlalleri eşik altında; rapor arşivleniyor
- Artefakt: `playwright-report/` Actions altında mevcut

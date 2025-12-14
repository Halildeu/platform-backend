---
title: "Documentation Workflow (Reporting Layout & Access Toast)"
status: draft
owner: "@team/frontend"
last_review: 2025-11-15
---

Bu doküman tailwind tabanlı reporting layout hikâyeleri, Chromatic otomasyon adımları ve cypress/playwright komutlarını CI pipeline’ına bağlamak için uygulanması gereken süreci özetler.

## 1. Storybook / Chromatic
1. `npm run storybook -- --docs` ile lokal doğrulama yap.
2. Reporting layout hikâyesini `docs/theme/theme-tokens.stories.mdx` altına ve `frontend/apps/mfe-reporting/src/stories/ReportPage.stories.tsx` dosyasına ekle.
3. Chromatic entegrasyonu için `.github/workflows/chromatic.yml` workflow’u eklenmiştir:
   ```yaml
   name: Chromatic
   on: [push, pull_request]
   jobs:
     chromatic:
       runs-on: ubuntu-latest
       env:
         SENTRY_DSN: ""
       steps:
         - uses: actions/checkout@v4
         - uses: actions/setup-node@v4
           with:
             node-version: 20
         - run: npm ci
         - run: npm run build-storybook
         - run: npx chromatic --project-token ${{ secrets.CHROMATIC_PROJECT_TOKEN }} --storybook-build-dir=storybook-static --exit-once-uploaded
   ```
4. PR açıklamalarına Chromatic run linki eklenir; screenshot/regression sonuçları QA tarafından kontrol edilir.
## 2. Cypress Users Permissions Senaryosu
- Senaryo: `frontend/cypress/e2e/users/permissions-inline.cy.ts`
  - `/api/users/all`, `/api/users/by-email/:email`, `/api/permissions/assignments` endpoint’leri fixture’larla stub’lanır.
  - Tailwind inline warning (`data-testid="module-warning-user_management"`) görünürlüğü doğrulanır.
- Remote’lar: shell 3000, reporting 3007, users 3004, access 3006.
- Otomasyon adımları:
  1. `npm run prestart`
  2. `npm run dev:remotes &` (script: `scripts/ci/start-remotes.sh`)
  3. `sleep 30 && env -u ELECTRON_RUN_AS_NODE npm run cypress:reports`
  4. Testler bittikten sonra `pkill -f webpack.dev.js || true`
## 3. Access Toast + Playwright Smoke
- Playwright testleri: `tests/playwright/reporting.a11y.spec.ts` (Reporting guard) + `tests/playwright/access.toast.spec.ts` (Access toast).
- `npm run smoke:playwright` script’i her iki dosyayı çalıştırır.
- Senaryo akışı:
  1. Shell dev sunucuları (3000, 3004, 3006, 3007) açılır (`npm start`).
  2. `access.toast.spec.ts` `/admin/access` sayfasına gider, role klonlama butonunu tetikler ve `window.__capturedToasts` aracılığıyla `app:toast` event’ini doğrular.
  3. `test-results/playwright-report/` ve `test-results/access.toast-*` klasörleri artefakt olarak CI run’ına yüklenir.
  4. Sonuç acceptance log’una (ALPHA-04) eklenir (ör. “`npm run smoke:playwright` → 2/2 senaryo yeşil, Access toast spec CI#123 artefaktı”).

---
title: "Variant Manager Test Plan"
status: in_review
owner: "@team/frontend"
workflow_tickets:
  - QA-01
last_review: 2025-11-12
---

## 1. Amaç ve Kapsam
- Grid Variant Manager (Reporting MFE + gelecekteki modüller) için regresyon güvenliği sağlamak.
- Kapsam:
  - Remote ReportingApp içindeki `useGridVariants` kancası.
  - Variant servis entegrasyonu (`/api/variants`, `/api/variants/:id/preference`).
  - Shell deep-link’leri (`?variant=` query) ve global varsayılan davranışı.
- Bu plan QA-01 bileti kapsamındaki E2E + Jest testlerini tarif eder.

## 2. Cypress E2E Matris

| Senaryo | Dosya / Fixture | Kapsam | Not |
| --- | --- | --- | --- |
| Token yoksa login’e yönlenir, izin yoksa unauthorized | `cypress/e2e/reports-routing.cy.ts` | Guard zinciri, `/admin/reports/*` alias | FE-03 dokümantasyonu ile uyumlu |
| Global default uygulanır | `cypress/e2e/variants/global-default.cy.ts` + `variants/global-only.json` | `isGlobalDefault` seçimi | Mevcut |
| Kullanıcı varsayılanı globali ezer | Aynı dosya + `variants/user-default.json` | `isUserDefault` → UI state | Mevcut |
| Kullanıcı seçim sonrası preference patch edilir | Aynı dosya + `variants/team-view-selected.json` | PATCH `/preference` + sonraki fetch | Mevcut |
| `?variant=` query override | Aynı dosya | Query > dropdown state | Mevcut |
| **Yeni**: API hata durumunda toast + fallback | `cypress/e2e/variants/preference-failure.cy.ts` (eklenecek) | QA-01 aksiyon | Intercept PATCH 500 |
| **Yeni**: Variant listesi boş → dropdown disabled | Aynı test dosyası (fixture) | QA-01 aksiyon | |

Komut: `pnpm --filter frontend cypress run --spec "cypress/e2e/variants/*.cy.ts"`  
Lokal hızlı deneme: `pnpm --filter frontend cypress open --e2e`.

## 3. Jest / Unit Test Planı
- **Hook testi**: `useGridVariants`
  - Mock `variant-service` çağrıları (`fetchGridVariants`, `updateVariantPreference`).
  - Senaryolar: loading → success; error -> `variantsError`, `message.warning`.
  - Araç: `apps/mfe-reporting/src/modules/shared/__tests__/useGridVariants.spec.tsx` (eklenecek).
- **Selector testi**: Variant seçimi öncelik sırası (`isUserSelected` > `isUserDefault` > `isDefault` > `isGlobalDefault`).
- **Query param binding**: `ReportPage` içindeki `queryVariantId` logic.

## 4. Test Verisi & Fixtures
- `frontend/cypress/fixtures/variants/*.json` dosyaları:
  - `global-only.json` — sadece global default.
  - `user-default.json` — user default + global.
  - `global-and-team.json` — birden fazla varyant.
  - `team-view-selected.json` — preference patch yanıtı.
- QA-01 ile birlikte yeni fixture’lar:
  - `empty-list.json`
  - `preference-error.json` (hata mesajı senaryosu).

## 5. Çalıştırma ve CI
- CI pipeline: `.github/workflows/reporting-ci.yml`
  - Adımlar: `npm ci` → `npm run test:reporting` (build + jest) → `npx start-server-and-test "npm run start:reports" ... "npm run cypress:reports"`.
  - Pipeline düşerse **Actions → Reporting - Guard & Variant Tests** altında ilgili job’ı açıp shell/reports server log’larını ve Cypress çıktısını inceleyin. Çökme anında `npm run start:reports` servislerinden biri ayakta değilse log tail’i otomatik attach edilir.
- Lokal / manuel doğrulama:
  1. `npm install`
  2. Ayrı terminalde `npm run start:reports` (shell 3000 + reporting 3007’i başlatır).
  3. Test terminalinde `npm run cypress:reports` (sadece `/admin/reports/*` guard + variant senaryoları).
- CI artefact planı: Cypress çıktıları Actions run’ına junit + video olarak yüklenir (pipeline defaultu). Gerekirse `reports/variant-manager/` klasörüne manuel export edilip arşivlenebilir.

## 6. Açık Aksiyonlar (QA-01)
1. Yeni Cypress senaryoları (`preference-failure`, `empty-list`) yaz ve fixtures ekle.
2. `useGridVariants` için Jest testi yaz; `variant-service` mock’ları `__mocks__` altında tutulacak.
3. README güncellemesi: `frontend/docs/04-tests/02-e2e/` altına kısa referans ekle.
4. CI job eklemeleri (GitHub Actions `frontend-e2e.yml`).

Bu plan tamamlandığında QA-01 kartı “in-progress/done” akışına alınacaktır.

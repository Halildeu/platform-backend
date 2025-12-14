---
title: "FE-06 — Reports/Audit Deep-Link Uygulama Planı"
status: in_review
owner: "@team/frontend"
workflow_tickets:
  - FE-06
last_review: 2025-11-12
---

Özet
- Reports (users/audit) modülleri ile Users/Audit MFE’leri arasında çift yönlü deep-link akışı.
- Query → filtre bağlama ve CTA link üretimi; guard ve E2E doğrulama.

Uygulama Adımları (Frontend repo: ../frontend)
1) Reporting MFE modülleri
   - `reports.users` → `createInitialFilters(params)`: `userId`, `search`, `status`.
   - `reports.audit` → `createInitialFilters(params)`: `auditId`, `search`, `level`, `service`.
   - `createHref(mod, query)` util’i: yukarıdaki parametreleri whitelist edin.
2) Users MFE CTA
   - “Raporlar”/“Detay” → `/admin/reports/users?userId=<id>&search=<q>&status=<s>`.
   - Mutasyonlarda `notifyAuditId(auditId)`; toast “Detay” → `/admin/reports/audit?auditId=<id>`.
3) Audit MFE CTA
   - “Kullanıcıyı Gör” → `/admin/users?userId=<id>`.
   - “Raporu Gör” → `/admin/reports/audit?auditId=<id>`.
4) Shell guard & menü
   - `VIEW_REPORTS` izni ile `/admin/reports/*` erişimi.
   - Unauthorized → `/unauthorized` (state.from korunur).

E2E Senaryoları (Cypress — frontend)
- Dosya: `cypress/e2e/reports-audit-deeplink.cy.ts`
- Senaryolar:
  1) Token yok → `/login` redirect
  2) İzin yok → `/unauthorized`
  3) Users → Reports (users): `userId` preset + filtreler
  4) Users → Reports (audit): `notifyAuditId` sonrası “Detay” → audit raporu
  5) Audit → Users: “Kullanıcıyı Gör” → Users `userId` preset
  6) Deep-link URL: `/admin/reports/audit?auditId=...&level=error`

Çalıştırma (lokal)
    # Shell + Reporting + Audit dev
    npm run start:reports
    # Cypress yalnız bu spec
    npm run cypress:reports -- --spec cypress/e2e/reports-audit-deeplink.cy.ts

Observability
- `Operations / Reports Guard` (UID: OPS-REPORTS-GUARD-001) panelleri `/admin/reports/*` için hazır.
- Gerekirse `/admin/audit/*` path’leri için ek panel ekleyin; Loki sorgularını path’e göre uyarlayın.

Kapanış (Acceptance)
- 2 ardışık yeşil CI koşusu (artefakt yüklü)
- Unauthorized ratio paneli veri üretiyor (evaluation OK)
- CTA’lar doğru URL’leri üretip presetleri uyguluyor

Durum
- Cypress spec eklendi ve komuta dahil edildi (`cypress:reports`). Lokal ortamda Cypress binary doğrulaması başarısız olduğu için CI koşusu bekleniyor.

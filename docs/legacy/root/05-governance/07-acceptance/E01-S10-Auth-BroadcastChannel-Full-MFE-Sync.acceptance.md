title: "Acceptance — E01-S10 Auth BroadcastChannel Full MFE Sync"
status: done
related_story: E01-S10
progress:
  ready: 2025-11-28T23:05:00+03:00
  in_progress: 2025-11-28T23:10:00+03:00
  done: 2025-11-28T23:55:00+03:00
---

Story ID: E01-S10-Auth-BroadcastChannel-Full-MFE-Sync

Checklist
- [x] Tüm MFE kod tabanında token erişimi Shell servisleriyle sınırlandı (users/reporting shell-services + shared-http resolver güncellemeleri).
- [x] BroadcastChannel veya fallback mekanizması ile sekmeler arası logout senaryosu manuel/otomatik testlerle doğrulandı (`frontend/tests/playwright/auth.sync.spec.ts` ve storage event tetikleyici).
- [x] localStorage/sessionStorage içinde uzun süreli token saklayan kod kalmadı (shell auth slice + Playwright/Cypress yardımcıları güncellendi).
- [x] İlgili güvenlik/gizlilik dokümanlarında strateji güncellendi (FRONTEND-ARCH-STATUS, STYLE-FE-001, BACKEND-ARCH-STATUS).
- [x] Keycloak `serban` realm’inde user/permission/variant audience scope’ları tanımlandı; frontend client bu scope’ları varsayılan olarak talep ediyor ve backend servisleri SECURITY_JWT_AUDIENCE gevşetmeleri olmadan yalnız kendi kaynak adlarını doğruluyor.

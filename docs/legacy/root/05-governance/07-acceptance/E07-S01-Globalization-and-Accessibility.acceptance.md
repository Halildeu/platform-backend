---
title: "Acceptance — E07-S01 Globalization and Accessibility"
status: pending
related_story: E07-S01
---

Story ID: E07-S01-Globalization-and-Accessibility

Checklist
- [x] TMS → `@mfe/i18n-dicts` pipeline’ı tanımlandı ve en az bir dict setiyle çalıştı.
- [x] Pseudolocale ile en az bir kritik ekran üzerinde görsel/içerik testi yapıldı.  
  - Kanıt: pseudo locale (zz) + fallback zinciri (zz → tr → en) ile login/register/unauthorized ve manifest tabanlı Users/Access ekranlarında smoke yapıldı; layout bozulmadı, eksik key durumunda fallback devreye girdi.
- [x] Missing key telemetry: i18n_missing_key event’i telemetry’ye iletiliyor (PII yok).  
  - Kanıt: I18nManager onMissingKey handler’ı (frontend/apps/mfe-shell/src/app/i18n/I18nManager.ts) namespace/key/locale/fallback bilgisini telemetry event’i olarak gönderiyor.
- [x] FE telemetry event’leri (page/action/mutation) tipli olarak gönderiliyor.  
  - Kanıt: Shell route change → page_view; Users sayfası “refresh” → action_click; Access toolbar aksiyonları → action_click; rol izin kaydet mutasyonu → mutation_commit (duration + auditId).
- [x] A11y smoke (lokal): axe tabanlı test eklendi ve lokal çalıştırıldı.  
  - Kanıt: `frontend/tests/playwright/axe/axe.smoke.spec.ts`, komut: `npm run test:a11y` (Playwright + axe, login/users/access) lokal PASS; CI entegrasyonu sonraki fazda yapılacak.
- [ ] Tema/renk erişilebilirliği için `docs/05-governance/06-specs/SPEC-E03-S01-THEME-LAYOUT-V1.md` ve `docs/05-governance/06-specs/SPEC-E07-S01-GLOBALIZATION-A11Y-V1.md` altındaki kontrast ve focus kuralları uygulanmış; Light/Dark/High-Contrast tema profillerinde gövde metni için AA, kritik alanlar için AAA, ikon ve focus halkası için gerekli oranlar doğrulanmıştır.
- [ ] `docs/05-governance/07-acceptance/QA-02.md` (QA-02 Visual & A11y) senaryoları, en az bir grid ekranı ve bir form ekranı için güncel tema setleri (`shell-light`, `shell-dark`, `shell-hc`, `shell-compact`) altında koşulmuş; gece/gündüz görünmeyen öğeler (breadcrumb, disabled, placeholder, tooltip, filter chip, pinned ayrımı, focus halkası) checklist’e göre taranmış ve kalan kritik ihlaller kapatılmıştır.

---
title: "QLTY-FE-SPA-LOGIN-01 – Acceptance"
status: pending
owner: "@team/frontend"
related_story: QLTY-FE-SPA-LOGIN-01
last_review: 2025-11-24
---

- [ ] `/login` rotası shell içinde SPA bileşeni olarak render edilir; Keycloak ekranı yalnızca bizim UI üzerindeki login butonu çağrıldığında açılır.
- [ ] silent-check-sso ve token yenileme döngüsü ProtectedRoute + auth provider tarafından yönetilir; redirect parametresiyle kullanıcı login sonrası önceki sayfaya döner.
- [ ] 401/403 durumlarında otomatik logout ve redirect davranışı tanımlıdır; shell toast/hata bildirimi tasarımı dokümante edilmiştir.
- [ ] FRONTEND-ARCH-STATUS’ta “SPA Login Flow” bölümü güncellenmiş; login sayfası, ProtectedRoute, silent-check-sso akışı ve redirect kuralları açıklanmıştır.
- [ ] `PROJECT_FLOW.md` ve session-log (tag `[FE-SPA-LOGIN-PLAN]`) girişleri güncel; QA notu LoginPage unit/e2e testlerinin çalıştığını doğrular.

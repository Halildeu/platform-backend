---
title: "QLTY-FE-SHARED-HTTP-01 – Acceptance"
status: done
owner: "@team/frontend"
related_story: QLTY-FE-SHARED-HTTP-01
last_review: 2025-11-24
---

- [x] `@mfe/shared-http` (axios tabanlı) paket’i gateway baseURL (`VITE_GATEWAY_URL || http://localhost:8080/api`) ve `/api/v1/**` path’lerini tekilleştirir; tüm MFE’ler bu paketi kullanır.
- [x] Authorization header injection, optional internal API anahtarı ve X-Trace-Id üretimi interceptor üzerinden merkezi olarak uygulanır.
- [x] 401/403 durumlarında shell logout veya login redirect tetiklenir; davranış FRONTEND-ARCH-STATUS “Shared HTTP Layer” bölümünde belgelenmiştir.
- [x] `PROJECT_FLOW.md` satırı ✔ olarak işaretlenmiş; session-log’da `[FE-SHARED-HTTP-PLAN]` + ilgili uygulama kaydı yer alır.
- [x] En az bir MFE için lint/test/build veya Playwright smoke testi shared-http refactor sonrası yeşil sonuçlanmıştır.

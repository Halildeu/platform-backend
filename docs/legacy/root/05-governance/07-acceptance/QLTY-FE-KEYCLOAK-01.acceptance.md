---
title: "ACCEPTANCE – QLTY-FE-KEYCLOAK-01"
story_id: QLTY-FE-KEYCLOAK-01
status: done
owner: "@team/frontend-arch"
last_review: 2025-11-30
modules:
  - frontend-shell
  - shared-http
  - api-gateway
  - ops-ci
---

# 1. Amaç
Prod/test ortamlarında shell + MFE’lerin Keycloak OIDC client’ı ile login/register/refresh/logout akışını güvenli şekilde yürüttüğünü, dev/local profillerinde permitAll davranışının kontrollü biçimde sürdürüldüğünü doğrulamak.

# 2. Traceability (Bağlantılar)
- **Epic:** `docs/05-governance/01-epics/E01_Identity.md`
- **Story:** `docs/05-governance/02-stories/QLTY-FE-KEYCLOAK-01-Frontend-Keycloak-OIDC.md`
- **Spec:** `docs/05-governance/06-specs/SPEC-QLTY-FE-KEYCLOAK-01-FRONTEND-KEYCLOAK-OIDC.md`
- **ADR:** ADR-003, ADR-010
- **ARCH:** `frontend/docs/01-architecture/01-shell/01-frontend-architecture.md`, `backend/docs/01-architecture/01-system/01-backend-architecture.md`
- **PROJECT_FLOW:** QLTY-FE-KEYCLOAK-01 satırı

# 3. Kapsam (Scope)
Keycloak client konfigürasyonu, shell auth store + BroadcastChannel paylaşımı, `@mfe/shared-http` interceptor’ları, dev/local permitAll sınırları, CI smoke testleri ve ilgili dokümantasyon.

> `modules` alanı ve aşağıdaki başlıklar AGENT-CODEX §6.3.1 gereği hangi modüllerin etkilendiğini kanıtlar.

# 4. Acceptance Kriterleri (Kontrol Listesi)

### Frontend Shell
- [x] keycloak-js init → login → refresh → logout akışı prod/test profillerinde eksiksiz çalışır; ProtectedRoute yetkisiz kullanıcıyı `/login?redirect=` ile karşılar (`frontend/tests/smoke/artifacts/keycloak-shell-flow.log`, login.svg).  
- [x] BroadcastChannel + storage fallback tüm sekmeleri aynı anda logout eder; token yalnız bellek içinde tutulur, local/session storage’a yazılmaz (logout.svg + evidence §3 notları).  
- [x] Dev/local profillerinde permitAll modu `VITE_AUTH_MODE=permitAll` ile sınırlıdır ve dokümante edilmiştir (`frontend/docs/01-architecture/01-shell/01-frontend-architecture.md` + session-log 2025-11-30 girdisi).

### Shared HTTP / API Gateway
- [x] `@mfe/shared-http` axios interceptor tüm `/api/v1/**` çağrılarına `Authorization: Bearer <token>` ve `X-Trace-Id` header’larını STL-API-001’e uygun olarak ekler (bkz. `packages/shared-http/src/index.ts` + `gateway-smoke.log`).  
- [x] Token yenileme veya süresi dolduğunda interceptor 401 yanıtını shell login akışına yönlendirir; gereksiz tekrar istek veya sonsuz döngü yoktur (unit testler + unauthorized handler senaryoları).  
- [x] Gateway logları prod/test’te yalnız RS256 token kabul ettiğini gösterir; dev profilinde permitAll çağrıları loglanır (`frontend/tests/smoke/artifacts/gateway-smoke.log` mock harness’i Authorization header’ı doğruluyor, 401/200 örnekleri eklendi).

### Ops / CI
- [x] security-guardrails pipeline’ındaki Node/lint adımları (`npm run i18n:pseudo`, `tokens:build`, `lint:style`, `lint:tailwind`, `lint:semantic`) lokal olarak doğrulandı; çıktılar `frontend/tests/smoke/artifacts/lint-semantic.log` altında tutuldu.  
- [x] `.env` / README / ARCH dokümanlarında `VITE_KEYCLOAK_*`, `VITE_AUTH_MODE`, `VITE_ENABLE_FAKE_AUTH` notları güncellendi; session-log’da FE-KEYCLOAK-FLOW-01 satırı rollout kaydını içeriyor.  
- [x] Gateway 401/200 curl smoke zinciri (geçerli token → 200, hatalı token → 401) `tests/smoke/run-gateway-smoke.sh` harness’i ile çalıştırıldı ve log eklendi.

# 5. Test Kanıtları (Evidence)
- [x] Vitest – `apps/mfe-shell`: `npx vitest run --environment jsdom src/pages/login/LoginPage.ui.test.tsx`  
- [x] Vitest – `packages/shared-http`: `npx vitest run --root ../../packages/shared-http src/index.test.ts`  
- [x] Gateway response örnekleri (traceId maskeli): `frontend/tests/smoke/artifacts/gateway-smoke.log`  
- [x] Security guardrails komut logu: `frontend/tests/smoke/artifacts/lint-semantic.log`  
- [x] Ekran görüntüleri (login, logout, permitAll modu) – `docs/05-governance/07-acceptance/evidence/img/QLTY-FE-KEYCLOAK-01/*.svg`
- Ayrıntılı açıklama ve komut dökümleri: `docs/05-governance/07-acceptance/evidence/QLTY-FE-KEYCLOAK-01.md`

# 6. Sonuç
Genel Durum: done  
Tüm modül checklist’leri sağlandığında Story PROJECT_FLOW’da ✔ Done’a çekilecek, session-log’a kapanış satırı eklenecektir.

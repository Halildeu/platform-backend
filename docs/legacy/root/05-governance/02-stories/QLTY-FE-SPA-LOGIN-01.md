---
title: "QLTY-FE-SPA-LOGIN-01 – SPA Login & silent-check-sso"
status: planned
owner: "@team/frontend"
last_review: 2025-11-24
---

## Amaç
- Shell içinde /login sayfasının tamamıyla bizim UI’mız tarafından yönetildiği, Keycloak ekranına gitmeden silent-check-sso + redirect akışının tamamlandığı bir SPA login deneyimi sağlamak.
- ProtectedRoute ve global auth context’in session yenileme (token refresh), redirect sonrası route restore ve hata durumlarını kapsaması.
- Güvenlik mimarisinin FRONTEND-ARCH-STATUS dokümanında “SPA Login Flow” olarak belgelenmesi.

## Kapsam
- `mfe-shell` içinde LoginPage UI, Keycloak client init ve silent-check-sso orkestrasyonu; `keycloak.login()` yalnız backendle senkronize butondan tetiklenecek.
- ProtectedRoute + shell auth provider: session timeout, token refresh, redirect parametreleri (`?redirect=`) ve yetki kontrolü.
- Shell ile remote MFE’ler arasında auth state paylaşımı (BroadcastChannel veya context) ve fallback logout/401 yönlendirmeleri.
- Doküman güncellemeleri: FRONTEND-ARCH-STATUS Security bölümü, PROJECT_FLOW satırı, session-log plan kaydı.
- Testler: LoginPage unit testleri, ProtectedRoute smoke testi, silent-check-sso happy path ve error path (Playwright/Manual).

## Durum
- Kod: TODO
- Doküman: TODO
- Test: TODO

## Acceptance (özet)
- `docs/05-governance/07-acceptance/QLTY-FE-SPA-LOGIN-01.acceptance.md`

## Bağlantılar
- `frontend/frontend/docs/01-architecture/01-shell/01-frontend-architecture.md`
- `docs/05-governance/PROJECT_FLOW.md`
- `docs/00-handbook/session-log.md`

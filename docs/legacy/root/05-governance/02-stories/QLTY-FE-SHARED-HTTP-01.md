---
title: "QLTY-FE-SHARED-HTTP-01 – Ortak HTTP istemcisi"
status: done
owner: "@team/frontend"
last_review: 2025-11-24
---

## Amaç
- Tüm MFE’lerde HTTP çağrılarının tek `@mfe/shared-http` paketi üzerinden yapılması; baseURL, Authorization, X-Trace-Id ve hata işleme interceptor’larının merkezi hale getirilmesi.
- Gateway baseURL ve `/api/v1/**` path normalizasyonunu frontend tarafında zorunlu kılmak; webpack proxy bağımlılıklarını kaldırmak.

## Kapsam
- `packages/shared-http` veya `@mfe/shared-http` paketinin axios instance + interceptor katmanıyla yayınlanması.
- Her MFE’nin service katmanı `api` helper’ını kullanacak şekilde refactor edilmesi (users, access, reporting, audit, suggestions, ethic).
- Token/401 redirect davranışı: Keycloak token’ı header’a ekleyen, 401’de ProtectedRoute/shell logout akışını tetikleyen interceptor.
- Doküman güncellemeleri: FRONTEND-ARCH-STATUS “Shared HTTP Layer” bölümü, PROJECT_FLOW satırı, session-log kaydı.
- Lint/test: shared-http paketinin unit testleri; en az bir MFE’de smoke testi (Playwright) ile gateway proxy/traceId doğrulaması.

## Durum
- Kod: DONE
- Doküman: DONE
- Test: DONE

## Acceptance (özet)
- `docs/05-governance/07-acceptance/QLTY-FE-SHARED-HTTP-01.acceptance.md`

## Bağlantılar
- `frontend/frontend/docs/01-architecture/01-shell/01-frontend-architecture.md`
- `docs/05-governance/PROJECT_FLOW.md`
- `docs/00-handbook/session-log.md`

---
title: "QLTY-API-V1-STANDARDIZATION-01 – /api/v1 standardizasyonu"
status: planned
owner: "@team/platform-arch"
last_review: 2025-11-24
---

## Amaç
- Backend servisleri ve frontend istemcilerinin tamamının `/api/v1/**` path’lerini ve STYLE-API-001 pagination/sort/search/advancedFilter sözleşmesini tekilleştirmesi.
- PagedResult (`items/total/page/pageSize`) zarfının, hata modeli (`ErrorResponse`) ve versiyonlama kurallarının tüm API dokümanlarında güncel olması.
- Yeni standardın PROJECT_FLOW, ARCH-STATUS (backend+frontend) ve STYLE-API-001 referanslarında izlenebilir hale getirilmesi.

## Kapsam
- Servis controller’larında legacy `/api/...` path’lerinin `@Deprecated` olarak işaretlenmesi ve v1 uçlarının resmi rota olması (users, roles, permissions, variants, auth).
- FE service katmanlarının `@mfe/shared-http` üzerinden `/api/v1/...` path’lerini kullanması ve pagination/sort/search parametrelerini STYLE-API-001’e göre normalize etmesi.
- API dokümanları (`docs/03-delivery/api/*.md`) ile BACKEND-ARCH-STATUS “API Versioning” bölümü ve FRONTEND-ARCH-STATUS “v1 Service Layer Alignment” bölümünün güncellenmesi.
- QA/Manual checklist: v1 response zarfı + hata modeli + traceId/headers, session-log plan kaydı `[API-V1-STANDARDIZATION-PLAN]`.

## Durum
- Kod: TODO
- Doküman: TODO
- Test: TODO

## Acceptance (özet)
- `docs/05-governance/07-acceptance/QLTY-API-V1-STANDARDIZATION-01.acceptance.md`

## Bağlantılar
- `docs/00-handbook/STYLE-API-001.md`
- `docs/03-delivery/api/README.md`
- `docs/01-architecture/01-system/01-backend-architecture.md`
- `frontend/frontend/docs/01-architecture/01-shell/01-frontend-architecture.md`

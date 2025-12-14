---
title: "User Service — API Rehberi"
status: draft
owner: "@team/backend"
last_review: 2025-11-12
---

Genel
- Base URL (Gateway): `/api/users`
- Kimlik: `Authorization: Bearer <JWT>` zorunlu; bağlamsal başlıklar `X-Company-Id`, `X-Project-Id`, `X-Warehouse-Id` desteklenir.

Başlıklar
- `Authorization: Bearer <JWT>`
- `X-Company-Id: <id>` (opsiyonel fakat önerilir)
- `X-Project-Id`, `X-Warehouse-Id` (opsiyonel)

Listeleme (SSRM uyumlu)
- GET `/api/users/all`
- Sorgu parametreleri:
  - `page`, `pageSize` — SSRM ile uyumlu sayfalama
  - `search` — hızlı arama (name/email)
  - `status=ALL|ACTIVE|INACTIVE|INVITED|SUSPENDED`
  - `role=ALL|USER|ADMIN`
  - `sort` — çoklu: `field,dir;field2,dir2` (örn. `name,asc;email,desc`)
  - `advancedFilter` — URL-encoded JSON model (whitelist)
- Yanıt (200):
```json
{
  "items": [
    {
      "id": "1",
      "fullName": "Jane Doe",
      "email": "jane@example.com",
      "role": "USER",
      "status": "ACTIVE",
      "lastLoginAt": "2025-11-10T10:00:00Z",
      "createdAt": "2025-11-01T08:00:00Z",
      "sessionTimeoutMinutes": 15,
      "modulePermissions": [
        { "moduleKey": "USER_MANAGEMENT", "moduleLabel": "Kullanıcı Modülü", "level": "VIEW" }
      ]
    }
  ],
  "total": 1234,
  "page": 1,
  "pageSize": 50
}
```

CSV Export (Büyük veri)
- GET `/api/users/export.csv`
- Başlıklar: `Accept: text/csv`
- Parametreler: `search`, `status`, `role`, `sort`, `advancedFilter`
- Rate-limit & Audit: Guard servisindedir; p95 süresi ve blocked ratio Grafana paneli ile izlenir.

Kullanıcı Kayıt/Güncelleme (örnek)
- POST `/api/users/register`
```json
{ "name": "Yeni Kullanıcı", "email": "yeni@example.com", "password": "***" }
```
- PUT `/api/users/{id}` — Gövde: kısmi güncelleme (`name`, `role`, `enabled`, `sessionTimeoutMinutes`)

Hata Yönetimi
- 401/403: yetkisiz/izin yok
- 400: geçersiz `advancedFilter` veya `sort`
- 500: beklenmeyen hata (requestId log’da)

İzleme/Metrikler
- `users_search_requests_total{mode,has_search,has_advanced_filter,has_sort,result}`
- `users_search_duration` (histogram)
- Export: `users_export_rate_limit_total`, `users_export_audit_total`, `users_export_duration`

Postman / Insomnia
- Postman koleksiyonu: `docs/03-delivery/api/postman/Users-Permissions-API.postman_collection.json`
- Insomnia export: `docs/03-delivery/api/insomnia/insomnia-export.yaml`

---
title: "Permission Service — API Rehberi"
status: draft
owner: "@team/backend"
last_review: 2025-11-12
---

Genel
- Base URL (Gateway): `/api/permissions`
- Kimlik: `Authorization: Bearer <JWT>`; bağlam için `X-Company-Id` vb. başlıklar desteklenir.

Rol & Atama (örnek uçlar)
- GET `/api/permissions/assignments?userId=<id>` — Kullanıcının modül bazlı atamaları
- PATCH `/api/permissions/roles/bulk` — Rol bazlı toplu güncelleme
- POST `/api/permissions/assignments` — Modül erişimi atama
- DELETE `/api/permissions/assignments/{assignmentId}` — Atama kaldırma

Assignment Yanıt Örneği
```json
[
  {
    "moduleKey": "USER_MANAGEMENT",
    "moduleLabel": "Kullanıcı Yönetimi",
    "level": "MANAGE",
    "assignmentId": "a-123",
    "roleName": "USER_MANAGER",
    "permissions": ["VIEW_USERS", "EDIT_USERS", "MANAGE_USERS"],
    "companyId": "42"
  }
]
```

İzin Kontrolü (Gateway/Service-to-service)
- GET `/api/permissions/check?userId=<id>&companyId=<id>&action=VIEW_USERS`
- Yanıt: `{ "allowed": true }`

Hata Kodları
- 400: parametre eksik/geçersiz
- 403: yetki yok
- 404: assignment/rol bulunamadı

İyi Uygulamalar
- Modül/izin sabitleri tek kaynaktan: `PERMISSIONS` sabiti ve backend enum’ları
- Rol → seviye eşlemesi (VIEW/EDIT/MANAGE) döküman ve kodda senkron tutulmalı
- Postman / Insomnia
  - Postman koleksiyonu: `docs/03-delivery/api/postman/Users-Permissions-API.postman_collection.json`
  - Insomnia export: `docs/03-delivery/api/insomnia/insomnia-export.yaml`

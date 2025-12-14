title: "QLTY-REST-USER-01 – Acceptance"
status: done
owner: "@team/backend"
last_review: 2025-11-29
---

- [x] `/api/v1/users` listeleme items/total/page/pageSize zarfıyla döner; advancedFilter/sort whitelist uygulanır.
- [x] `/api/v1/users/{id}` ve `/api/v1/users/by-email` UserDetailDto döner, bulunamazsa 404.
- [x] `/api/v1/users/{id}/activation` aktif/pasif değiştirir; 204 döner.
- [x] Legacy `/api/users/*` uçları deprecated olarak belgelenmiştir; davranışı korunur.
- [x] `docs/03-delivery/api/users.api.md` v1/legacy ayrımını içerir.

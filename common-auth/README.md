# common-auth

Ortak yetkilendirme yardımcıları ve sabitleri.

- `AuthorizationContextBuilder`: JWT’den userId/email/permissions/roles çıkarır. `permissions` claim’i multivalued string listesi olarak beklenir; hasPermission büyük/küçük harf duyarsızdır.
- Cache/TTL önerisi: permission-service’den gelen scope/permission setleri 5–10 dakikalık TTL ile (in-memory veya Redis) cache’lenmelidir; builder sonucunu her istek için yeniden oluşturmak yerine cache edilen scope yanıtıyla birleştirin (bkz. SPEC-QLTY-BE-AUTHZ-SCOPE-01).

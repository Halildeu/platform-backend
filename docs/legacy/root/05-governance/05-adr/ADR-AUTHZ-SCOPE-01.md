# ADR-AUTHZ-SCOPE-01 — Permission-service ile scope’lu merkezi yetki modeli

**Durum:** Accepted  
**Tarih:** 2025-11-30  
**Karar Sahibi:** @halil  
**İlgili Dokümanlar:**  
- Story: `docs/05-governance/02-stories/QLTY-BE-AUTHZ-SCOPE-01.md`  
- Spec: `docs/05-governance/06-specs/SPEC-QLTY-BE-AUTHZ-SCOPE-01-AUTHZ-SCOPED-PERMISSIONS.md`  
- Acceptance: `docs/05-governance/07-acceptance/QLTY-BE-AUTHZ-SCOPE-01.acceptance.md`  
- Style: `docs/00-handbook/NAMING.md`  

## 1. Context
- İzin stringleri servislerde hard-coded ve permission-service’de karşılığı olmayan kayıtlar var (örn. MANAGE_GLOBAL_VARIANTS).  
- Scope (project/warehouse vb.) bazlı yetki bilgisi merkezi bir yerde tutulmuyor; servisler kendi yorumluyor.  
- Keycloak claim’leri ile permission-service katalogu arasında naming/senkron standardı eksik.  
- S2S akışı Auth-service + Vault ile çalışmaya devam ediyor; user-facing token’lar Keycloak’ta üretiliyor.

## 2. Decision
- Permission-service, scope’lu izinlerin merkezi kaynağı olacak:
  - `permissions` sözlüğü (code, module, description)
  - `user_permission_scope` (user_id, permission_code, scope_type, scope_id)
  - Opsiyonel `role_permission` referansı (Keycloak rol eşleşmeleri için).
- Keycloak mapper’ları permission-service kodlarını prefix’siz `permissions` claim’ine yazacak; PermissionCodes ile bire bir isimlendirme.  
- Ortak `PermissionCodes`, `ScopeTypes`, `AuthorizationContext` kütüphanesi oluşturulacak; servisler yetkiyi buradan okuyacak.  
- Pilot: variant-service (veya user-service) AuthorizationContext ile scope filtresi uygular; FE filtresi ∩ allowedScopes.  
- api-gateway RS256/audience doğrulamasına devam edecek; S2S Auth-service + Vault akışı değişmeyecek.

## 3. Alternatives
- **Servis içi hard-coded izinler (mevcut durum):** Dağınık stringler, permission-service ile uyumsuz; scope yönetimi yok.  
- **Tam Zanzibar/tuple-store:** Aşırı karmaşık; mevcut kapsam için gereksiz, maliyet yüksek.  
- **Sadece Keycloak rolleriyle devam:** Scope bilgisi token’da yok; veri filtresi backend’de güvenli uygulanamıyor.

## 4. Consequences
- İyi: Tek kaynaktan izin/scope yönetimi; kod/Keycloak/permission-service tutarlılığı; scope’lu veri erişimi güvenli.  
- Kötü: Ek migrasyon ve ortak kütüphane ihtiyacı; cache/TTL stratejisi doğru seçilmezse performans riski.  
- Devam eden: S2S token akışında değişiklik yok; Auth-service + Vault yapısı korunuyor.

## 6. Fiil Sözlüğü ve İsimlendirme Standardı
- Fiiller (çekirdek): READ, CREATE, UPDATE, DELETE, EXPORT, IMPORT.  
- Süreç fiilleri: SUBMIT, APPROVE, REJECT, CANCEL, CLOSE, REOPEN, POST, UNPOST, TRANSFER, SIGN.  
- Sistem fiilleri: USER_MANAGE, ROLE_MANAGE, PERMISSION_MANAGE, SYSTEM_CONFIGURE, AUDIT_READ.  
- İsimlendirme: `<DOMAIN>_<ACTION>[_<QUALIFIER>]` (örn. PROJECT_READ, WAREHOUSE_STOCK_TRANSFER, VARIANTS_MANAGE_GLOBAL).

## 7. Yol Haritası (Özet)
- Faz 1: permission-service şeması (permissions, roles, role_permissions, scopes, user_permission_scope, authz_audit_log) + migrasyonlar.  
- Faz 2: common-auth (PermissionCodes, ScopeTypes, AuthorizationContext).  
- Faz 3: authzService.buildContext + pilot servis (scope filtreleme, cache).  
- Faz 4: Yaygınlaştırma, güvenlik testleri, drift kontrolü (katalog–Keycloak–kod uyumu).
- Faz 5: access-service, audit-service, user-service için scope filtresi yaygınlaştırması (QLTY-BE-AUTHZ-SCOPE-02); scope kapalı servisler (örn. variant-service) sapma olarak story/acceptance’da belgelemek zorunda.

## 5. Traceability / Etkilenenler
- Modüller: permission-service, Keycloak, api-gateway, variant-service (pilot), user-service (takip)  
- SPEC: `docs/05-governance/06-specs/SPEC-QLTY-BE-AUTHZ-SCOPE-01-AUTHZ-SCOPED-PERMISSIONS.md`  
- Story: `docs/05-governance/02-stories/QLTY-BE-AUTHZ-SCOPE-01.md`

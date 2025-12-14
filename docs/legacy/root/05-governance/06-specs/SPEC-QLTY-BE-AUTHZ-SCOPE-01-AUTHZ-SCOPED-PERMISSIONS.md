# SPEC-QLTY-BE-AUTHZ-SCOPE-01 — Scope’lu Permission Kataloğu ve AuthorizationContext

**Başlık:** Scope’lu Permission Kataloğu ve AuthorizationContext  
**Versiyon:** v1.0  
**Tarih:** 2025-11-30  

**İlgili Dokümanlar:**  
- EPIC: `docs/05-governance/01-epics/QLTY.md` (varsa)  
- ADR: `docs/05-governance/05-adr/ADR-AUTHZ-SCOPE-01.md`  
- ACCEPTANCE: `docs/05-governance/07-acceptance/QLTY-BE-AUTHZ-SCOPE-01.acceptance.md`  
- STORY: `docs/05-governance/02-stories/QLTY-BE-AUTHZ-SCOPE-01.md`  
- STYLE GUIDE: `docs/00-handbook/NAMING.md`  

**Etkilenen Modüller / Servisler:**  

| Modül/Servis       | Açıklama / Sorumluluk                          | İlgili ADR |
|--------------------|------------------------------------------------|------------|
| permission-service | Permission ve scope ilişkilerini merkezi tutar | -          |
| Keycloak           | permissions claim’ine PermissionCodes yazar    | -          |
| api-gateway        | RS256/audience doğrulama, claim passthrough    | ADR-010    |
| variant-service    | Pilot scope filtresi, AuthorizationContext     | -          |
| user-service       | Takip eden entegrasyon                         | -          |

## 1. Amaç (Purpose)
Permission-service’i merkezi, scope’lu izin kataloğu haline getirmek; Keycloak claim’leri ve servis tarafı AuthorizationContext katmanını standartlaştırmak.

## 2. Kapsam (Scope)

### Kapsam içi
- Permission-service veri modeli ve API’leri
- Keycloak claim hizalaması (permissions claim’i)
- Ortak authz kütüphanesi (PermissionCodes, ScopeTypes, AuthorizationContext)
- Pilot uygulama: variant-service veya user-service

### Kapsam dışı
- Tam Zanzibar tuple-store implementasyonu
- Yetki yönetimi UI tasarımı
- S2S token akışının değiştirilmesi (Auth-service + Vault aynı kalır)

## 3. Tanımlar (Definitions)
- PermissionCodes: İzin stringlerinin merkezi seti.  
- ScopeTypes: PROJECT, COMPANY, WAREHOUSE vb. kapsam tipleri.  
- AuthorizationContext: JWT + permission-service scope’larından türetilen context.

## 4. Kullanıcı Senaryoları (User Flows)
- Yetkili kullanıcı: permissions claim’i ve scope eşleşmesi varsa veri görebilir.  
- Yetkisiz scope: permissions var ama scope eşleşmiyorsa 0 kayıt veya 403 döner.  
- Claim yok: permissions claim’i yoksa 403.

## 5. Fonksiyonel Gereksinimler (Functional Requirements)
- FR-01: Permission-service’de permission kataloğu ve scope tablosu migrate edilmiş olmalı.  
- FR-02: `GET /authz/user/{id}/scopes` permission→scope setlerini cache dostu formatta döndürmeli.  
- FR-03: Keycloak shell token’ında permissions claim’i PermissionCodes ile bire bir isimlendirilmiş olmalı (prefix yok).  
- FR-04: AuthorizationContext builder JWT’den permissions’ı okuyup permission-service’den scope setlerini çekerek cache’lemeli.  
- FR-05: Pilot serviste sorgular allowed scope set’i ile filtrelenmeli; yetkisiz scope için 0 kayıt veya 403 dönmeli.

## 6. İş Kuralları (Business Rules)
- BR-01: permissions claim yoksa pilot endpoint’ler 403 döner.  
- BR-02: FE filtreleri allowed scope set’i ile kesişime göre uygulanır; kesişim boş ise sonuç boş döner.  
- BR-03: PermissionCodes/ScopeTypes isimleri Keycloak mapper, permission-service ve kodda bire bir aynı olmalıdır.

## 7. Fiil Sözlüğü ve İsimlendirme Standardı
- Her zaman şu dört eylemden biri kullanılır: READ, CREATE, UPDATE, DELETE.  
- İsimlendirme: `<service>-<action>` formatı, tamamı küçük harf ve tire ile ayrık.  
  - Örnekler: `users-read`, `users-create`, `audit-read`, `variant-service-update`.  
- Bu isimler permission-service katalogu, PermissionCodes ve Keycloak mapper’larında bire bir aynı olmalıdır.

## 8. Veri Modeli (Data Model)
- `permissions` (code PK, module, description)
- `roles` (code, description) — opsiyonel ama önerilir  
- `role_permissions` (role_code FK → roles, permission_code FK → permissions)  
- `scopes` (id, scope_type, ref_id, parent_scope_id, description, created_at/updated_at)  
- `user_permission_scope` (user_id, permission_code FK, scope_type, scope_id, created_at)  
- `authz_audit_log` (actor_user_id, action, target_type, target_id, old_value, new_value, created_at)

## 9. API Tanımı (API Spec)
- `GET /authz/user/{id}/scopes`
  - Dönen payload: `{ permission: "VARIANTS_WRITE", scopes: [{type:"PROJECT", ids:[1,2]}], ... }`
  - Cache başlığı/ETag desteği, 5–10 dk TTL önerisi.
- Yönetim uçları (ops):
  - `POST /authz/permissions` (katalog ekleme/güncelleme)
  - `POST /authz/user/{id}/scopes` (ilişki ekleme/silme)

## 10. Validasyon Kuralları (Validation Rules)
- `user_id` zorunlu, UUID formatı.  
- `permission_code` permission sözlüğünde bulunmalı.  
- `scope_type` tanımlı enum değerlerinden biri olmalı.

## 11. Hata Kodları (Error Codes)
| Kod       | HTTP | Açıklama                         |
|-----------|------|----------------------------------|
| AUTHZ-401 | 401  | Geçersiz/eksik JWT               |
| AUTHZ-404 | 404  | Kullanıcı veya scope bulunamadı  |

## 12. Non-Fonksiyonel Gereksinimler (NFR)
- Performans: Scope sorguları cache’lenmeli (TTL 5–10 dk).  
- Güvenlik: RS256 + audience doğrulama; token maskeleme loglarda.  
- Audit: permission katalog/ilişki değişiklikleri audit tablosuna/loga yazılır.  
- Operasyon: Vault/secret yönetimi mevcut; Auth-service S2S akışı değişmez.

## 13. İzlenebilirlik (Traceability)
- Story: `docs/05-governance/02-stories/QLTY-BE-AUTHZ-SCOPE-01.md`  
- Acceptance: `docs/05-governance/07-acceptance/QLTY-BE-AUTHZ-SCOPE-01.acceptance.md`  
- ADR: `docs/05-governance/05-adr/ADR-AUTHZ-SCOPE-01.md`

## 14. İlke ve Mimarinin Özeti
- Yetki iki eksenli: NE (permission) + NEREDE (scope).  
- permission-service control-plane: permission + scope ilişkisi tek kaynak; runtime’da cache/replica üzerinden okunur.  
- Keycloak: kullanıcı kimlik + rol dağıtımı, JWT üretimi; yalnız NE bilgisini (permissions claim) taşır, scope backend’de permission-service’ten türetilir.  
- Mikro servis: JWT’den userId + permissions → `authzService.buildContext(jwt)` ile allowedCompany/Project/Warehouse set’leri çıkarılır; her liste/GET isteğinde FE filtresi ∩ allowed scopes uygulanır.  
- Frontend güvenilmez varsayılır; kullanıcı filtre göndermese bile backend scope filtresi zorunludur, yetkisiz ID enjekte edilse sonuç 0 kayıt.

## 15. Backend Sorgu / Frontend Davranışı
- Etkin filtre: `effectiveIds = (feFilter boş ise allowedIds, dolu ise kesişim(feFilter, allowedIds))`; kesişim boş ise 0 kayıt.  
- SQL örneği: `WHERE project_id = ANY(:effectiveProjectIds)` + diğer filtreler.  
- Tekrarı azaltma: ScopedRepository/Specification veya ortak ScopedQueryBuilder ile aynı pattern tüm entitelerde kullanılır.  
- Opsiyonel: Kritik tablolarda PostgreSQL RLS “defense in depth” olarak ileriki fazda değerlendirilebilir.

## 16. Güvenlik, Audit ve Testler
- Audit: permissions/roles/role_permissions/user_permission_scope CRUD değişiklikleri için `authz_audit_log` (actor, action, target_type, old/new).  
- Riskli izinler (USER_MANAGE, PERMISSION_MANAGE, SYSTEM_CONFIGURE, AUDIT_READ) ileriki fazda onay akışıyla verilebilir; ilk fazda audit’te vurgulanır.  
- Test paketi (her serviste en az 1–2 kritik endpoint):  
  - Yetkili kullanıcı → yetkili scope’da 200 + veri; yetkisiz scope’da 0/403  
  - Filtre yok → sadece allowed scope kayıtları gelir  
  - Çoklu scope → kesişim mantığı doğrulanır  
- Drift kontrolü (ileriki iyileştirme): permission-service kataloğu, common-auth PermissionCodes ve Keycloak rol/mapper konfigürasyonu arasında uyumsuzluk raporu.

## 17. Yol Haritası (İş Paketleri)
- Faz 1: permission-service şeması (permissions, roles, role_permissions, scopes, user_permission_scope, authz_audit_log) + migrasyonlar; minimal CRUD API’ler (`/permissions`, `/roles`, `/roles/{roleCode}/permissions`, `/authz/user/{userId}/scopes`).  
- Faz 2: `backend/common-auth` kütüphanesi (PermissionCodes, ScopeType, AuthorizationContext); SecurityConfig’lerde permissions claim’i GrantedAuthority’ye çevrilir.  
- Faz 3: authzService.buildContext implementasyonu (permission-service’ten scope çekme + cache) ve pilot servis (ör. variant-service) entegrasyonu; FE filtresi ∩ allowed scopes pattern’i uygulanır.  
- Faz 4: Diğer servislere yayılım, güvenlik testleri (ID injection vs.), gerekirse kritik tablolarda RLS değerlendirmesi.
- Faz 5 (QLTY-BE-AUTHZ-SCOPE-02): access-service, audit-service, user-service’de permission-service kaynaklı scope filtresi uygulanır; scope kapatılan servisler (örn. variant-service) sapma olarak story/acceptance’da belgelenir.

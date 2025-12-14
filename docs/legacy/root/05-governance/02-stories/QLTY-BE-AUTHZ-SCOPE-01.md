# Story QLTY-BE-AUTHZ-SCOPE-01 — Permission-service merkezli scope’lu yetkilendirme

- Epic: QLTY – Güvenlik/Yetkilendirme iyileştirmeleri  
- Story Priority: 999  
- Tarih: 2025-11-30  
- Durum: In Progress  
- Modüller / Servisler: permission-service, Keycloak, api-gateway, variant-service (pilot), user-service (takip)

## 1. Kısa Tanım
Permission-service’in scope’lu izin kataloğu olarak konumlandırılması; Keycloak claim’lerinin PermissionCodes ile hizalanması ve mikro servislerde AuthorizationContext katmanıyla scope bazlı filtrelemenin standardize edilmesi.

## 2. İş Değeri
- Yetki stringlerinin tek kaynaktan yönetilmesiyle kod/Keycloak/permission-service tutarlılığı sağlanır.
- Scope bazlı erişim (project/warehouse vb.) backend’de dayatılır; UI manipülasyonu riskini azaltır.
- Tekrarlayan yetki kontrolleri için ortak AuthorizationContext ve cache ile performans/okunabilirlik kazanımı.

## 3. Bağlantılar (Traceability Links)
- SPEC: `docs/05-governance/06-specs/SPEC-QLTY-BE-AUTHZ-SCOPE-01-AUTHZ-SCOPED-PERMISSIONS.md`  
- ACCEPTANCE: `docs/05-governance/07-acceptance/QLTY-BE-AUTHZ-SCOPE-01.acceptance.md`  
- ADR: `docs/05-governance/05-adr/ADR-AUTHZ-SCOPE-01.md`  
- FEATURE REQUEST: FR-013 (`docs/05-governance/FEATURE_REQUESTS.md`)  
- STYLE GUIDE: `docs/00-handbook/NAMING.md`

## 4. Kapsam (Scope)

### In Scope
- Permission-service’de permission sözlüğü + user_permission_scope (scope’lu ilişki) şemalarının tanımlanması.
- `GET /authz/user/{id}/scopes` API’si ve yönetim uçları (katalog/ilişki CRUD).
- Keycloak mapper’larının PermissionCodes ile hizalanması, permissions claim’ine yazılması.
- Ortak `PermissionCodes`, `ScopeTypes`, `AuthorizationContext` kütüphanesi.
- Pilot servis (variant-service veya user-service) entegrasyonu ve scope filtrasyonu.

### Out of Scope
- Tam Zanzibar tuple-store implementasyonu.
- Yetki yönetimi UI tasarımı (yalnız API sağlanır).
- S2S token akışının değiştirilmesi (Auth-service + Vault aynı kalır).
- Variant-service için scope dayatması şu anda kapalı; yalnız permission kontrolü uygulanıyor. Diğer servisler (access/audit/user vs.) için scope kuralı ilerleyen iterasyonlarda devreye alınacak; doküman takibi bu Story üzerinden yapılacak.

## 5. Tanımlar (Definitions)
- PermissionCodes: Ortak izin string seti (örn. MANAGE_GLOBAL_VARIANTS, VARIANTS_WRITE).
- ScopeTypes: PROJECT, COMPANY, WAREHOUSE vb. kapsam tipleri.
- AuthorizationContext: JWT + permission-service scope’larından üretilen context nesnesi.

## 6. Task Flow (Ready → InProgress → Review → Done)

```text
+--------------------+-------------------------------------------------------------+------------+-------------+---------+------+
| Modül/Servis       | Task                                                        | Ready      | InProgress  | Review  | Done |
+--------------------+-------------------------------------------------------------+------------+-------------+---------+------+
| permission-service | permissions + user_permission_scope şeması ve migrasyon     | 2025-11-30 |             |         | ✔    |
| permission-service | /authz/user/{id}/scopes API + /authz/me uçları              | 2025-11-30 |             |         | ✔    |
| Keycloak           | permissions claim mapperlarını PermissionCodes ile hizala   | 2025-11-30 |             |         |      |
| shared-auth lib    | PermissionCodes, ScopeTypes, AuthorizationContext builder   | 2025-11-30 |             |         | ✔    |
| variant-service    | Pilot entegrasyon: scope filtreleme + PermissionCodes kullan | 2025-11-30 | ✔           |         |      |
| api-gateway        | İlgili audience/claim doğrulama kontrollerini doğrula       | 2025-11-30 |             |         |      |
| ops-ci             | Cache/TTL ve audit/runbook güncellemeleri                   | 2025-11-30 |             |         |      |
+--------------------+-------------------------------------------------------------+------------+-------------+---------+------+
```

## 7. Fonksiyonel Gereksinimler
1. Permission-service’de permission kataloğu ve scope tablosu migrate edilmiş olmalı.  
2. `GET /authz/user/{id}/scopes` kullanıcıya ait permission→scope setlerini cache dostu formatta döndürmeli; `GET /authz/me` login kullanıcının izin/scope özetini vermeli.  
3. Keycloak shell token’ında permissions claim’i PermissionCodes ile bire bir isimlendirilmiş olmalı (sadece login; business izin kararı permission-service verisi).  
4. AuthorizationContext builder JWT’den permissions’ı okuyup permission-service’den scope setlerini çekerek cache’lemeli (TTL dokümante).  
5. Pilot serviste sorgular allowed scope set’i ile filtrelenmeli; yetkisiz scope için 0 kayıt veya 403 dönmeli.

## 8. Non-Functional Requirements
- RS256 + audience doğrulama korunacak; permission-service API’leri resource-server modunda.  
- Cache stratejisi (TTL, in-memory/Redis) dokümante edilecek.  
- Loglarda traceId ve audit girdileri permission değişikliklerini yansıtacak.

## 9. İş Kuralları / Senaryolar
- “permissions claim var, scope eşleşiyor” → 200 ve ilgili kayıtlar dönmeli.  
- “permissions claim var, scope eşleşmiyor” → boş liste veya 403 (senaryoya göre).  
- “permissions claim yok” → 403 (pilot endpoint).

## 10. Interfaces (API / DB / Event)
- API: `GET /authz/user/{id}/scopes`, katalog/ilişki CRUD uçları.  
- Database: `permissions`, `user_permission_scope`, opsiyonel `role_permission`.  
- Events: (Şimdilik yok).

## 11. Acceptance Criteria
Güncel kabul kriterleri: `docs/05-governance/07-acceptance/QLTY-BE-AUTHZ-SCOPE-01.acceptance.md`

## 12. Definition of Done
- [ ] İlgili SPEC maddeleri karşılandı.  
- [x] Acceptance dosyasındaki maddeler sağlandı (scope filtresi variant-service için business kararıyla devre dışı, permission-only mod).  
- [x] Permission-service migrasyonları ve API’leri (scopes + /me) yayınlandı.  
- [x] Keycloak mapper’ları PermissionCodes ile hizalı, shell token’da doğrulandı (permissions claim doğrulandı; aud/iss: ok).  
- [x] Ortak kütüphane AuthorizationContext scope + TTL cache ile paketlendi.  
- [x] Pilot servis AuthorizationContext ile entegre (variant-service: permissions temelli authz, scope kısıtı yok; testler güncel).  
- [x] Dokümantasyon (runbook/session-log/PROJECT_FLOW) güncellendi.  
- [x] Testler (unit/integration) ve manuel smoke senaryoları yeşil (admin/admin1 401 almıyor; scope kapalı).  
- [x] Kod review onayı alındı.

## 13. Notlar
- Büyük scope tablolarında cache/TTL seçimi performansı etkileyebilir; izleme metrikleri eklenmeli.  
- Mevcut izin isimlerinin geriye dönük uyumu için geçiş planı gereklidir.
- Variant-service pilotunda scope kesişimi kaldırıldı (business kararı); permission-only modda çalışıyor. Scope tabanlı filtreleme başka servislerde yaygınlaştırılacaksa bu Story altındaki task/acceptance güncellemeleriyle izlenecek.

## 14. Dependencies
- QLTY Epic, FR-013.  
- RS256/JWT guardrail’leri (ADR-010) ile uyum.  
- Vault/Keycloak mevcut mimarisi.

## 15. Risks
- Cache stratejisinin yanlış seçilmesi, yetki sızıntısı veya performans problemleri.  
- İsimlendirme tutarsızlığı; Keycloak, permission-service ve kodda aynı stringler kullanılmalı.
- Takip (yaygınlaştırma): access-service / audit-service / user-service için scope tabanlı kontroller ayrı iş olarak açılacak; variant-service permission-only modda kalacak.

## Flow / Iteration İlişkileri
| Flow ID | Durum   | Not |
|---------|---------|-----|
| Flow-Security-Backend | Planned | Scope’lu permission standardizasyonu |

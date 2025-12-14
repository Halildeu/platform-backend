# Story QLTY-BE-AUTHZ-SCOPE-02 — Scope’lu AuthZ’nin Access/Audit/User Servislerine Yaygınlaştırılması

- Epic: QLTY – Güvenlik/Yetkilendirme iyileştirmeleri  
- Story Priority: 998  
- Tarih: 2025-12-03  
- Durum: Devam ediyor  
- Modüller / Servisler: permission-service, access-service, audit-service, user-service, api-gateway (JWT doğrulama), Keycloak (login)

## 1. Kısa Tanım
Permission-service kaynaklı permission+scope modelinin access-service, audit-service ve user-service içinde standart hale getirilmesi; scope (PROJECT/COMPANY/WAREHOUSE vb.) kısıtlarının AuthorizationContext ile uygulanması, Keycloak’ta yalnız login/token görevinde kalınması.

## 2. İş Değeri
- İş yetkisi tek kaynaktan (permission-service) çekilerek tutarlılık sağlanır.  
- Scope manipülasyonu riskleri azaltılır; FE filtresi + allowedScope kesişimi backend’de zorunlu kılınır.  
- Ortak AuthorizationContext/Cache ile performans ve okunabilirlik kazanımı elde edilir.

## 3. Bağlantılar (Traceability Links)
- SPEC: `docs/05-governance/06-specs/SPEC-QLTY-BE-AUTHZ-SCOPE-01-AUTHZ-SCOPED-PERMISSIONS.md`  
- ACCEPTANCE: `docs/05-governance/07-acceptance/QLTY-BE-AUTHZ-SCOPE-01.acceptance.md` (genel çerçeve)  
- Story (pilot): `docs/05-governance/02-stories/QLTY-BE-AUTHZ-SCOPE-01.md`  
- ADR: `docs/05-governance/05-adr/ADR-AUTHZ-SCOPE-01.md`  
- STYLE: `docs/00-handbook/NAMING.md`

## 4. Kapsam (Scope)
### In Scope
- access-service, audit-service, user-service için AuthorizationContext tüketimi.  
- permission-service `/authz/user/{id}/scopes` çağrısının cache’li kullanımı.  
- FE filtreleri ile allowedScope kesişimi (uygun scope tipleri için).  
- İlgili unit/integration testleri ve smoke kanıtları.

### Out of Scope
 - Variant-service global katalogdur; company/project data-scope uygulanmaz, /authz/me yalnız permissions için kullanılır.  
- Keycloak’ta business role/permission yönetimi (yalnız login/token).  
- Zanzibar benzeri global tuple-store.

## 5. Tanımlar
- PermissionCodes: Ortak izin string seti.  
- ScopeTypes: PROJECT/COMPANY/WAREHOUSE vb.  
- AuthorizationContext: JWT + permission-service scopes → cache’li context.

## 6. Task Flow (Ready → InProgress → Review → Done)

```text
+--------------------+------------------------------------------------------+------------+-------------+---------+------+
| Modül/Servis       | Task                                                 | Ready      | InProgress  | Review  | Done |
+--------------------+------------------------------------------------------+------------+-------------+---------+------+
| permission-service | ScopeType + user_role_scope migration, /authz/me&scopes DTO/Query güncellemeleri |            |             |         | 2025-12-04 |
| permission-service | AccessControllerV1 → user-role scope CRUD (COMPANY/PROJECT) |            |             |         | 2025-12-04 |
| user-service       | allowedScopes repo filtresi (company tablosu eklendiğinde) + testler | 2025-12-03 | 2025-12-04  |         |      |
| variant-service    | AuthorizationContext + /authz/me hook iskeleti (scope TODO) | 2025-12-04 |             |         |      |
| audit-service      | AuthorizationContext + scope filtresi + testler      | 2025-12-03 |             |         |      |
| access (tüketiciler)| Scope filtresi tasarım notu; veri modeli hazır olunca uygulanacak | 2025-12-03 |             |         |      |
| api-gateway        | JWT issuer/audience mevcut, ek değişiklik gerekmez   | 2025-12-03 |             |         |      |
+--------------------+------------------------------------------------------+------------+-------------+---------+------+
```

## 7. Fonksiyonel Gereksinimler
1. Her servis JWT’den permissions claim’ini okuyup permission-service’ten scope setlerini almalı ve AuthorizationContext ile cache’lemeli.  
2. İlgili endpoint’lerde FE filtreleri allowedScope ile kesiştirilmeli; scope yoksa 0 kayıt/403 stratejisi dokümante edilmeli.  
3. İzin isimleri PermissionCodes ile bire bir hizalı olmalı; Keycloak yalnız login/token üretmeli.

## 8. Non-Functional Requirements
- RS256 + audience guardrail korunacak.  
- Cache/TTL stratejisi servis bazında dokümante edilecek.  
- TraceId ve audit log’ları izin/scope kararını izlenebilir kılacak.

## 9. İş Kuralları / Senaryolar
- İzin var + scope eşleşiyor → 200 ve ilgili kayıtlar.  
- İzin var + scope eşleşmiyor → 200+boş veya 403 (servis bazında seçilecek, dokümante edilecek).  
- İzin yok → 403 (ilgili endpoint).

## 10. Acceptance Criteria (öneri)
- [ ] access-service: scope filtresi ve testler.  
- [ ] audit-service: scope filtresi ve testler.  
- [ ] user-service: scope filtresi ve testler.  
- [ ] Smoke: admin/admin1 benzeri iki kullanıcı ile 401/403 gözlemi; traceId log kanıtı.  
- [ ] Dokümantasyon/gov/runbook güncellendi; PROJECT_FLOW satırı işaretli.

## 11. Definition of Done
- [ ] Acceptance maddeleri sağlandı.  
- [ ] Kod review onayı alındı.  
- [ ] PROJECT_FLOW, session-log, gerekli runbook notları güncellendi.

## 12. Notlar
- Variant-service global katalogdur; company/project data-scope uygulanmaz, /authz/me yalnız permissions için kullanılır. Bu Story yalnız access/audit/user genişletmesini kapsar.  
- Gerekirse scope tipleri (PROJECT/COMPANY/WAREHOUSE) servis bazında sınırlandırılabilir; dokümana yazılacak.
- Kullanıcı → şirket ilişki tablosu henüz yok; user-service’de company scope filtresi bu tablo eklendiğinde devreye alınacak. Tablo yokken admin her zaman serbest, diğer kullanıcılar için company scope kontrolü uygulanamaz; bu sapma acceptance notlarında belirtilmeli. Şu an user-service’e AuthorizationContext/Client iskeleti eklendi, repo filtresi TODO olarak bırakıldı.
- İlerleme fazları (güncel plan): (1) permission-service scope migration + allowedScopes/isSuperAdmin alanları (/authz/me, /authz/user/{id}/scopes) ve AuthorizationQueryService güncellemesi. (2) AccessControllerV1’e user-role scope CRUD (şimdilik COMPANY/PROJECT). (3) user-service repo filtresi company ilişkisi tabloya eklendiğinde aktifleştirilecek. (4) audit/access tüketiciler için scope filtresi tasarım notu, veri modeli hazır olunca. (5) Governance/runbook/session-log her faz sonrası güncellenecek.
- Contract notu: `/api/v1/authz/me` ve `/api/v1/authz/user/{id}/scopes` response’larında `permissions`, `allowedScopes[{scopeType, scopeRefId}]`, `superAdmin` alanları bulunur. Scope CRUD uçları sadece `permission-scope-manage` yetkisi ile çağrılabilir; superAdmin=true ise data-scope filtreleri tüketici serviste bypass edilir.
- user-service için kısa kullanım notu `user-service/docs/AUTHZ-SCOPE-NOTES.md` altında tutulur; diğer servisler (audit/access) aynı AuthorizationContext + /authz/me pattern’ini kopyalayacak.
- Audit: AuditEventController’a audit-read / audit-export permission guard’ı eklendi; data-scope filtresi company/project kolonları eklendiğinde (SCOPE-03) açılacak.

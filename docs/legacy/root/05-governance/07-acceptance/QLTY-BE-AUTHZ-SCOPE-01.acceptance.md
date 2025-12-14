---
title: "ACCEPTANCE – QLTY-BE-AUTHZ-SCOPE-01"
story_id: QLTY-BE-AUTHZ-SCOPE-01
status: done
owner: "@halil"
last_review: 2025-12-02
modules:
  - permission-service
  - Keycloak
  - api-gateway
  - variant-service
  - user-service
---

# 1. Amaç
`QLTY-BE-AUTHZ-SCOPE-01` tesliminin tamamlanmış sayılabilmesi için gerekli kabul kriterlerini tanımlar. Acceptance, PROJECT_FLOW’daki “✔ Tamamlandı” geçişinin tek resmi kaynağıdır.

# 2. Traceability (Bağlantılar)
- **Epic:** `docs/05-governance/01-epics/QLTY.md` (varsa)  
- **Story:** `docs/05-governance/02-stories/QLTY-BE-AUTHZ-SCOPE-01.md`  
- **Spec:** `docs/05-governance/06-specs/SPEC-QLTY-BE-AUTHZ-SCOPE-01-AUTHZ-SCOPED-PERMISSIONS.md`  
- **ADR:** `docs/05-governance/05-adr/ADR-AUTHZ-SCOPE-01.md`  
- **PROJECT_FLOW:** QLTY-BE-AUTHZ-SCOPE-01 satırı

# 3. Kapsam (Scope)
1. Permission-service veri modeli ve API’leri  
2. Keycloak claim hizalaması (permissions)  
3. Ortak AuthorizationContext kütüphanesi  
4. Pilot servis entegrasyonu (variant-service veya user-service)  
5. Operasyon/guardrail ve dokümantasyon

# 4. Acceptance Kriterleri (Kontrol Listesi)

## Permission-service
- [x] `permissions` ve `user_permission_scope` tabloları migrate edildi; migration log/evidence eklendi.
- [x] `GET /authz/user/{id}/scopes` API’si izin→scope setlerini cache dostu formatta döndürüyor; 200/404/401 davranışı SPEC ile uyumlu.

## Keycloak
- [x] Shell (frontend) client mapper `permissions` claim’ine PermissionCodes’ları prefix’siz yazıyor.
- [x] JWT dump’ında login sonrası permissions listesi beklenen kodlarla görünüyor.

## Ortak Kütüphane
- [x] PermissionCodes + ScopeTypes + AuthorizationContext yayınlandı; TTL/cache stratejisi dokümante edildi.
- [x] `authzService.buildContext(jwt)` unit/integration testleri yeşil.

## Pilot Servis (variant-service veya user-service)
- [x] Permission kontrolü PermissionCodes ile yapılıyor (`hasPermission(...)` veya `hasAuthority(...)`).
- [x] Variant-service’de scope filtresi business kararıyla devre dışı; permission-only modda çalışıyor (permission-service tüketimi var, scope setleri kullanılmıyor).
- [x] Smoke: admin/admin1 token’ı ile variant/users grid çağrılarında 401 görülmedi; scope kapalı, permissions claim doğrulandı.

## Operasyon / Güvenlik / CI
- [x] RS256 + audience guardrail’leri korunuyor; permission-service API’leri resource-server modunda.
- [x] Vault/secret düzeni değişmedi; auth-service S2S akışı etkilenmiyor.
- [x] Runbook/session-log/PROJECT_FLOW güncellendi; security-guardrails pipeline yeşil.

# 5. Test Kanıtları (Evidence)
- Migration çıktıları, DB şema ekran görüntüsü  
- JWT dump (maskeli) permissions claim  
- Pilot servis 200/403 response örnekleri ve loglarda traceId  
- authzService/AuthorizationContext test sonuçları

# 6. Sonuç
Genel Durum: draft | review | ✔ done  
Tüm maddeler karşılandığında PROJECT_FLOW’da Story “✔ Tamamlandı” durumuna alınır ve session-log’a kapanış kaydı eklenir.

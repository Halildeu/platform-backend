---
title: "ACCEPTANCE – QLTY-BE-AUTHZ-SCOPE-02"
story_id: QLTY-BE-AUTHZ-SCOPE-02
status: planned
owner: "@halil"
last_review: 2025-12-03
modules:
  - permission-service
  - access-service
  - audit-service
  - user-service
  - api-gateway
---

# 1. Amaç
`QLTY-BE-AUTHZ-SCOPE-02` tesliminin tamamlanması için kabul kriterlerini tanımlar. Access/audit/user servislerinde permission-service kaynaklı permission+scope modelinin uygulanması hedeflenir.

# 2. Traceability (Bağlantılar)
- **Story:** `docs/05-governance/02-stories/QLTY-BE-AUTHZ-SCOPE-02.md`
- **Spec:** `docs/05-governance/06-specs/SPEC-QLTY-BE-AUTHZ-SCOPE-01-AUTHZ-SCOPED-PERMISSIONS.md`
- **ADR:** `docs/05-governance/05-adr/ADR-AUTHZ-SCOPE-01.md`
- **PROJECT_FLOW:** QLTY-BE-AUTHZ-SCOPE-02 satırı

# 3. Kapsam
1. Permission-service client + AuthorizationContext tüketimi (cache/TTL ile).
2. access-service, audit-service, user-service için scope tabanlı filtreleme (uygun scope tipleri).
3. Operasyon/runbook/gov güncellemeleri.

# 4. Acceptance Kriterleri (Kontrol Listesi)
## Permission-service
- [ ] `/authz/user/{id}/scopes` client DTO’ları servislerle hizalı; cache kullanımı dokümante.

## Servisler
- [ ] access-service: permission + allowedScope kesişimi uygulandı; 200/403/boş liste stratejisi dokümante; testler yeşil.
- [ ] audit-service: permission + allowedScope kesişimi uygulandı; 200/403/boş liste stratejisi dokümante; testler yeşil.
- [ ] user-service: permission + allowedScope kesişimi uygulandı; 200/403/boş liste stratejisi dokümante; testler yeşil (kullanıcı→şirket ilişki tablosu eklendiyse; tablo yoksa geçici olarak permission-only + admin bypass sapması dokümante).

## Keycloak / Güvenlik
- [ ] Keycloak yalnız login/token; permissions claim PermissionCodes ile hizalı (doğrulama kanıtı güncel).
- [ ] RS256 + audience guardrail korunuyor; S2S/Vault akışı değişmedi.

## Operasyon / Dokümantasyon
- [ ] Runbook/session-log/PROJECT_FLOW güncellendi.
- [ ] Smoke: en az iki kullanıcı profiliyle (ör. admin/admin1) 401/403/200 davranışları doğrulandı; traceId log kanıtı eklendi.

# 5. Test Kanıtları
- Unit/integration test çıktıları, log/trace örnekleri (maskeli).
- JWT dump (maskeli) permissions claim kanıtı.

# 6. Sonuç
Genel Durum: planned | in_progress | review | ✔ done  
Tüm maddeler karşılandığında PROJECT_FLOW’da Story “✔ Tamamlandı” durumuna alınır ve session-log’a kapanış kaydı eklenir.

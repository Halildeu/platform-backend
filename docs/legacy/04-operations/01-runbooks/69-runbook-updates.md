# Runbook Güncellemeleri & Kill-Switch Finalizasyonu

Faz 3 sonunda runbookların ve kill-switch raporunun güncel hale getirilmesi için yapılacaklar.

- 2025-12-02: Keycloak permissions mapper’ları PermissionCodes ile hizalandı; admin1@example.com token’ında permissions + aud/iss doğrulaması yapıldı. Ops notu: mapper/audience değişikliği yok, yalnız login amaçlı; yetki kaynağı permission-service.

## 1. Runbook Güncellemeleri
### 1.1 Unseal / Break-Glass
- `vault-runbook.md` içine ekler:  
  - Auto-unseal senaryosu (KMS) + manual fallback.  
  - Break-glass süresi ≤ 1 saat; incident commander onayı, audit log referansı.  
  - `vault-runbook-attestations/YYYY` altına tatbikat raporu.

### 1.2 Restore
- `vault-restore-drill.md` referansı; tatbikat raporu `restore-report-YYYYMMDD.md`.
- RPO/RTO hedefleri + lessons learned.

### 1.3 Secret & Cert Rotasyonu
- JWT / DB / mTLS rotasyon runbook linkleri:  
  - `service-jwt-keys.md`, `vault-db-secrets.md`, `vault-pki-mtls.md`, `jwt-rotation-plan.md`, `secret-rotation-pipeline.md`, `hot-reload-strategy.md`.
- Runbook’ta “Triger → Pipeline → Validation → Rollback” şablonu.

## 2. Kill-Switch Finalizasyonu
- `kill-switch-log-YYYYMMDD.md`:  
  - Tarih, katılımcılar, `security.legacy-api-key.enabled=false` doğrulaması.  
  - Legacy header için SIEM taraması (0 hit).  
  - Confluence sayfasına referans.
- CI job raporu ( `scripts/verify-service-jwt-env.sh`, internal key grep ) dökümana ekle.
- Checklist update: Faz 1 ve Faz 3 kill-switch maddeleri → “Completed (Tarih)” notu.

## 4. AuthZ Scope Notu (Permission-Service)
- `/api/v1/authz/me` ve `/authz/user/{id}/scopes` response alanları: `permissions`, `allowedScopes[{scopeType, scopeRefId}]`, `superAdmin`.
- `superAdmin=true` → data-scope filtreleri tüketici serviste bypass edilir.
- Scope CRUD uçları yalnız `permission-scope-manage` izniyle çağrılabilir; AccessControllerV1 için güvenlik testleri (MockMvc) eklendi.
- Tüketici servisler (user/audit/access) AuthorizationContext + `/authz/me` pattern’i ile entegre olur; company/project kolonları geldiğinde repo seviyesinde filtre açılır.

## 3. Onay & Depolama
- Runbook’lar imzalı PDF olarak güvenli kasaya/SharePoint’e.  
- Onaylayanlar: Platform Lead, Security Architect, Compliance Officer.  
- Yıllık gözden geçirme takvimi (Outlook calendar).

---
**Next:** Faz 3 kapanış checkleri (imzalar, Confluence linkleri, final rapor).

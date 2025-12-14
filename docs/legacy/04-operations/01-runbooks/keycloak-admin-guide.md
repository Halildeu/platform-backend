---
title: "Keycloak Admin Etkinlik Rehberi"
status: published
owner: "@security-platform"
last_review: 2025-11-04
tags: ["security", "keycloak", "observability"]
---

# Keycloak Admin Etkinlik Rehberi

Bu doküman, Keycloak Admin Console üzerindeki kullanıcı/rol değişikliklerini izlemek, loglamak ve incident anında incelemek için gereken bilgileri içerir.

## 1. Admin Console & Erişim

- URL: `https://keycloak.example.com/admin/`  
- Realm: `platform`  
- Audit log yetkisi olan roller: `kc-admin`, `security-auditor`.
- Üretim erişimi sadece break-glass onayı ile verilir. Onay kayıtları: Confluence → “Security Access Requests”.

## 2. Admin Event Loglama

- Keycloak ayar: `Realm Settings → Events → Admin Events`  
  - `Admin Events Enabled`: ✅  
  - `Include Representation`: ✅ (JSON body kaydı için)  
  - `Save Events`: ✅  
  - Retention: 30 gün (Keycloak DB) + 180 gün (SIEM).
- Syslog forwarder:  
  - Endpoint: `syslog://siem.example.com:6514` (mTLS)  
  - Format: JSON (`timestamp`, `realmId`, `clientId`, `resourceType`, `operationType`, `authDetails.userId`, `error`).

## 3. SIEM / Kibana Sorguları

- Index: `logs-keycloak-admin-*`  
- Sık sorgular:
  - Kullanıcı rol değişimi:  
    ```kql
    event:"admin_event" AND resource_type:"USER" AND details.roles:*
    ```
  - MFA politikası güncellemesi:  
    ```kql
    event:"admin_event" AND resource_type:"REALM" AND details.configKey:"otp.policy.period"
    ```
  - Client secret export:  
    ```kql
    event:"admin_event" AND resource_type:"CLIENT" AND operation_type:"ACTION" AND details.action:"EXPORT"
    ```
  - Hatalı admin giriş denemesi:  
    ```kql
    event:"admin_event" AND error:"insufficient_scope"
    ```

Saved Searches:
- **KC Admin – Role Updates** (`search-id: kc-admin-role-updates`)
- **KC Admin – MFA Changes** (`search-id: kc-admin-mfa`)
- **KC Admin – Client Secret Actions** (`search-id: kc-admin-client-secrets`)

## 4. Grafana Panoları

- Dashboard: `security/keycloak-admin-activity`
- Paneller:
  - `Admin Actions by Type` (bar chart)  
  - `Client Secret Exports` (table)  
  - `Failed Admin Login Attempts` (stat)  
  - `MFA Policy Changes` (alert panel)

Alerts:
- **Keycloak_Admin_Secret_Export**:  
  - `rate(keycloak_admin_secret_export_total[30m]) > 0`  
  - OpsGenie kanal: “Security Platform”  
  - Runbook: bu dokümanın §6
- **Keycloak_Admin_MFA_Change**:  
  - KQL filter + webhook; Slack `#security-alerts`  
  - Regex: `details.configKey:"otp.policy.*"`

## 5. Incident Prosedürü

1. Alert geldiğinde Kibana saved search ile ilgili kayıtları değerlendirin (corrId, userId, IP).  
2. Değişiklik planlı mı? Confluence Change log veya JIRA ticket eşleşmesini kontrol edin.  
3. Yetkisiz işlem şüphesi varsa:  
   - Keycloak → `Events` tabında kullanıcı oturumlarını sonlandırın.  
   - Vault AppRole / client secret’ları revoke edin (`docs/04-operations/01-runbooks/vault-restore-drill.md`).  
   - Incident kaydını açın (PagerDuty “Security Incident”).  
4. Raporu `security-incident` depoya ekleyin, bu dokümana referans verin.

## 6. Runbook / Referanslar

- `docs/01-architecture/04-security/identity/03-gateway-jwt-failover.md`
- `docs/04-operations/01-runbooks/01-vault-runbook.md`
- `docs/agents/03-secret-rotation-pipeline.md`
- `docs/04-operations/02-monitoring/security-dashboard.md`
- `docs/04-operations/01-runbooks/keycloak-realm-backup.md`

## 7. Break-glass Rotasyon Kontrolü

- **Frekans:** Aylık doc hygiene toplantısı (her ayın ilk pazartesi, platform-arch + security).  
- **Kaynaklar:**  
  - JIRA `SECURITY` projesi → filtre `status = "In Progress" AND summary ~ "Keycloak Access"`  
  - SIEM index `logs-keycloak-access-*` → `eventType:"keycloak_access_request_created"` ve `eventType:"keycloak_roles_revoked"`  
  - Vault secret: `secret/keycloak/breakglass` (aktif token listesi)
- **Adımlar:**  
  1. Açık break-glass taleplerinin bitiş tarihlerini kontrol et; süresi dolmuş talepleri revoke et.  
  2. Vault break-glass token’larını doğrula; kullanılmayanları `vault token revoke` ile iptal et.  
  3. SIEM dashboard’unda son 30 gün “keycloak_roles_revoked” eventi var mı kontrol et; yoksa cronjob çalışmasını incele.  
  4. Sonuçları doc hygiene toplantı notlarına ekle, backlog item’ını (gerekiyorsa) güncelle.

## 8. Açık Noktalar

- [ ] Keycloak admin yetki erişim isteği için self-service form (JIRA template) yayınlanacak.  
- [ ] Alert correlation için OPA policy logları ile etkinliklerin çapraz kontrolü planlanıyor.

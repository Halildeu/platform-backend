---
title: "Observability - Security Module"
status: published
owner: "@sre-team"
last_review: 2025-11-04
service: "mfe-security / gateway / auth perimeter"
tags: ["observability", "dashboard", "security"]
---

# Security Module Observability Rehberi

`mfe-security` mikro-frontend’i; gateway, Vault ve Keycloak ile entegrasyon halinde çalışır. Bu rehber, güvenlik katmanında izlenen kritik metrikler ve alert koşullarını dokümante eder.

## 1. Panolar

| Panel | Lokasyon | Açıklama |
| --- | --- | --- |
| **Security Overview** | Grafana → `security/security-overview` | JWT doğrulama hataları, mTLS ihlalleri, policy deny oranı |
| **Vault Client Metrics** | Grafana → `security/vault-clients` | Secret fetch süreleri, seal durumu, lease kapasitesi |
| **OPA Decision Audit** | Grafana → `security/opa-decisions` | Policy allow/deny dağılımı, latency, bundle sürümleri |
| **Gateway Auth Logs** | Kibana → index `logs-gateway-auth-*` | Gateway auth filter logları, corrId bazlı sorgular |
| **Access Requests** | Grafana → `security/keycloak-access` | `keycloak_access_request_created` event’lerinin sayısı ve detay logları |
| **Role Revoke Events** | Grafana → `security/keycloak-access` | `keycloak_roles_revoked` webhook event’leri için stat + log panelleri |
| **Audit Event Stream** | Grafana → `security/keycloak-access` | `fe.audit.*` telemetry event’lerinin canlı akışı (grid fetch, export, SSE fallback) |

### Panel Detayları

- **JWT Validation Failure:** `sum(rate(security_jwt_validation_failure_total[5m]))`
- **OPA Deny Ratio (policy filtresi):** `sum by (policy)(rate(opa_decision_denied_total[5m])) / sum by (policy)(rate(opa_decision_total[5m]))` → panel dropdown `policy` (default: `access-gateway`)
- **Vault Secret Fetch Duration:** `histogram_quantile(0.95, sum(rate(vault_client_secret_fetch_duration_seconds_bucket[5m])) by (le,client))`
- **Gateway mTLS Failure:** `sum(rate(gateway_mtls_handshake_failure_total[5m]))`
- **Access Request Count:** `sum(rate(keycloak_access_request_created_total[5m]))`
- **Role Revoke Count:** `sum(rate(keycloak_roles_revoked_total[5m]))`
- **Audit Stream Telemetry:** `sum by (type)(rate(fe_audit_events_total[5m]))` (Promtail ile shell telemetry → Loki pipeline’ı üzerinden beslenir)

## 2. Log Sorguları

- Auth failure zinciri: `service.name:"api-gateway" AND event:"jwt_validation_failed"`
- OPA deny detayları: `index="opa-audit-*" AND decision:"deny" AND input.resource:"/security/*"`
- Vault audit: `index="vault.audit.*" AND request.path:"/v1/secret/data/security"` (yanıt ve hata analizi)
- Keycloak admin: `logs-keycloak-*` → `event:"admin_event" AND resource_type:"USER"`
- Access request eventleri: `logs-keycloak-access-*` → `eventType:"keycloak_access_request_created"`
- Role revoke eventleri: `logs-keycloak-access-*` → `eventType:"keycloak_roles_revoked"`
- Audit telemetry stream: `logs-frontend-telemetry-*` → `event.type:\"fe.audit.*\"`

## 3. Alert Kuralları

| Alert | Koşul | Süre | Aksiyon |
| --- | --- | --- | --- |
| **Security_JWT_Failure_Spike** | `jwt_validation_failure_total > 5/min` | 10 dakika | PagerDuty “Security Perimeter”; `docs/01-architecture/04-security/identity/03-gateway-jwt-failover.md` adımlarını uygula |
| **Security_OPA_Deny_Surge** | `policy` bazlı OPA deny ratio > 0.2 | 5 dakika | Slack `#security-alerts`; policy bundle hash’ini kontrol et |
| **Security_Vault_Seal** | `vault_seal_status == 1` | Anında | On-call → `docs/04-operations/01-runbooks/01-vault-runbook.md` |
| **Security_Vault_Lease_Expiry** | Secret lease kalan süre < 15 dk | 10 dakika | `docs/04-operations/01-runbooks/vault-db-secrets.md` rotasyon prosedürü |
| **Security_mTLS_Failure** | `gateway_mtls_handshake_failure_total > 3/min` | 5 dakika | CI/CD manifest SRI + cert rotasyon kontrolü (`security/service-jwt-keys.md`) |
| **Security_Keycloak_Access_Surge** | `increase(keycloak_access_request_created_total[10m]) > 5` | Anında | Slack `#security-platform`; break-glass taleplerini gözden geçir |
| **Security_Keycloak_Revoke_Miss** | `increase(keycloak_roles_revoked_total[24h]) == 0` | Anında | Cronjob & SIEM pipeline’ı doğrula |
| **Security_Vault_Failfast_Restarts** | `increase(application_start_failed_total{reason="vault"}[5m]) >= 1` | Anında | PagerDuty “Security Perimeter”; `docs/04-operations/01-runbooks/vault-failfast-fallback.md` adımlarını uygula |

Alert kaynak dosyası: `observability/security-alerts.yaml` (grafana-mimir). Güncellediğinizde change record açın.

## 4. Runbook ve Referanslar

- `docs/01-architecture/04-security/identity/03-gateway-jwt-failover.md`
- `docs/04-operations/01-runbooks/01-vault-runbook.md`
- `docs/agents/01-security-architecture.md`
- `docs/04-operations/01-runbooks/52-mfe-access-runbook.md` (audit link fallback’lerinde referans)

## 5. Açık Noktalar

- [x] OPA deny paneline policy adı filtresi eklendi (`security-dashboard-improvements.md` §1).  
- [x] Vault audit ↔ mTLS failure korelasyon paneli eklendi (`security-dashboard-improvements.md` §2).  
- [x] Access request / revoke panelleri için alert kuralları (`security/keycloak-access` dashboard) configure edilip duyuruldu.

> Talepler için `#observability` veya `#security-platform` Slack kanallarına yazın.

## 6. Panel Linkleri ve Snapshot Arşivi

- OPA Deny Ratio (policy filtresi): <https://grafana.example.com/d/security/security-overview?viewPanel=opa_deny_ratio>
- Vault ↔ mTLS korelasyon paneli: <https://grafana.example.com/d/security/vault-clients?viewPanel=vault_mtls_correlation>
- Keycloak Access dashboard: <https://grafana.example.com/d/security/keycloak-access>

> Not: `Share → Snapshot` çıktıları `docs/04-operations/02-monitoring/assets/` altında `YYYYMM-<panel>.png` adıyla saklanmalı. Yeni snapshot eklendiğinde bu listede link güncelleyin.

### Slack Duyuru Şablonu

```
[#security-platform] Security dashboard güncellendi:
- OPA policy filtresi eklendi (security/security-overview)
- Vault ↔ mTLS korelasyon paneli eklendi (security/vault-clients)
- Keycloak access/revoke panelleri hazır; yeni Prometheus alert kuralları: infra/observability/alerts/security-dashboard.rules.yaml
```

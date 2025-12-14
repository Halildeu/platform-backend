---
title: "Security Dashboard Improvement Tasks"
status: published
owner: "@sre-team"
last_review: 2025-11-04
tags: ["observability", "security", "planning"]
---

# Security Dashboard Improvement Tasks

Bu belge, `security/security-overview` ve ilişkili panolarda planlanan geliştirmeleri kod / panel düzeyinde detaylandırır. Backlogdaki öğelerin uygulanması sırasında referans alınmalıdır.

## Hedef Takvim Özeti

- **Akış 3 (8–19 Aralık 2025):** OPA deny policy filtresi → ✅ tamamlandı (Grafana panel + alert).  
- **Akış 4 (5–16 Ocak 2026):** Vault ↔ mTLS korelasyon paneli → ✅ tamamlandı.  
- **Doc Hygiene (Her akış sonu):** Aşağıdaki checklist ile doğrulanmaya devam edecek.

## 0. SIEM Kaynağı

- CLI entegrasyonu (`scripts/jira/create-keycloak-access-request.mjs`) her başarılı talep sonrası `SIEM_WEBHOOK_URL` endpoint’ine aşağıdaki payload’u gönderir:
  ```json
  {
    "eventType": "keycloak_access_request_created",
    "ticket": "SECURITY-1234",
    "environment": "production",
    "role": "kc-admin",
    "accessType": "break-glass",
    "start": "2025-11-10T09:00:00Z",
    "end": "2025-11-10T11:00:00Z",
    "change": "SEC-456",
    "assignee": "@alice",
    "timestamp": "2025-11-03T12:01:00.000Z"
  }
  ```
- Kaydedilecek index önerisi: `logs-keycloak-access-*`. Dashboard’larda “Access Requests” paneli eklenebilir (TODO).

## 1. OPA Deny Panelinde Policy Filtresi

- **Durum:** ✅ Panel güncellendi (`security/security-overview?viewPanel=opa_deny_ratio`). Dropdown `policy` alanı ekli, default `access-gateway`.  
- **Prometheus sorgusu:**  
  ```promql
  sum by (policy)(rate(opa_decision_denied_total[5m])) /
  sum by (policy)(rate(opa_decision_total[5m]))
  ```
- **Alert güncellemesi:** `Security_OPA_Deny_Surge` alert’i label `policy` ile güncellendi (`infra/observability/alerts/security-dashboard.rules.yaml`).  
- **Bağımlılıklar:** OPA exporter ConfigMap (`infra/opa/exporter.yaml`) policy label’ını expose eder.

## 2. Vault Audit ↔ mTLS Korelasyon Paneli

- **Durum:** ✅ Grafana `security/vault-clients?viewPanel=vault_mtls_correlation` paneli (Logs + Stat + Prometheus grafiği) devrede.  
- **Log sorgusu:**  
  ```kql
  index="vault.audit.*" AND response.status:("denied" OR "failed") AND auth.decorated:true
  ```
- **Prometheus grafiği:**  
  ```promql
  sum(rate(gateway_mtls_handshake_failure_total[5m]))
  ```
- **Not:** Elasticsearch datasource `infra/observability/grafana-datasources.yaml` ile tanımlı. Alert kuralı `Security_Vault_mTLS_Correlation` Prometheus rule dosyasına eklendi.

## 3. İzleme Sonrası Güncelleme Checklist’i

- [x] Panel snapshot linkleri `docs/04-operations/02-monitoring/assets/README.md` altında arşivlendi (Grafana share URL’leri + tarih: 04.11.2025).  
- [x] Alert kaynak YAML (`infra/observability/alerts/security-dashboard.rules.yaml`) güncellendi.  
- [x] Prometheus/terraform pipeline’ı rule dosyasıyla güncellendi (`observability-alerts` deploy 2025-11-04).  
- [x] `docs/05-governance/02-roadmap/backlog.md` üzerindeki ilgili maddeler yeşile çekildi.  
- [x] Slack duyurusu yayınlandı (04.11.2025, `#observability`, `#security-platform`).  
- [x] Backstage dashboard kaydına versiyon notu eklendi (2025.11 güncellemesi).

> Not: Çalışmalar tamamlanana kadar bu doküman `status: in-progress` olarak kalacaktır; tamamlandığında `status: published` ve `last_review` alanları güncellenmeli.

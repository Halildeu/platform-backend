---
title: "Observability - Access Module"
status: draft
owner: "@sre-team"
last_review: 2025-11-03
service: "mfe-access"
tags: ["observability", "dashboard", "access"]
---

# Access Module Observability Rehberi

`mfe-access` manifest tabanlı rol yönetimi ekranının üretim sağlığını aşağıdaki panolar ve uyarılarla takip ediyoruz. Bu kayıt SRE ve Platform FE ekiplerinin ortak referansıdır.

## 1. Panolar

| Panel | Lokasyon | Açıklama |
| --- | --- | --- |
| **Access Overview** | Grafana → `fe-access/access-overview` | TTFA p95, mutation success ratio, client error rate, grid fetch latency |
| **Access Synthetic Checks** | Grafana → `fe-access/access-synthetic` | Her 5 dakikada yapılan `/access` sayfa yükleme testleri (TTFA, HTTP status) |
| **Access Error Logs** | Kibana → index `logs-fe-access-*` | Uygulama logları; correlation ID bazlı arama yapın |
| **Audit Link Telemetry** | Grafana → panel `fe.access.audit_link_clicks` | Notification center audit link tıklama sayıları |

### Panel Detayları

- **TTFA p95:** Query `histogram_quantile(0.95, sum(rate(fe_access_ttfa_bucket[5m])) by (le))`
- **Mutation Success Ratio:** `sum(rate(fe_access_mutation_success_total[5m])) / sum(rate(fe_access_mutation_total[5m]))`
- **Client Error Rate:** `sum(rate(fe_access_client_error_total[5m]))`
- **Grid Fetch Latency:** `histogram_quantile(0.95, sum(rate(fe_access_grid_fetch_bucket[5m])) by (le))`

> Not: Query isimleri Prometheus/YAML alert kurulumuyla uyumlu tutulur. Değişiklik yapılırsa `observability/security-tests.md` planı ve alert kural dosyası güncellenmeli.

## 2. Log Sorguları

- Genel hata: `service.name:"fe-access" AND level:("error" OR "fatal")`
- Mutasyon hata analizi: `event:"mutation_failed" AND attributes.auditId:*`
- Audit link telemetry: `event:"audit_link_click" AND attributes.route:"/access"`
- Performans outlier: `event:"ttfa_sample" AND attributes.value > 7000`

> Kibana saved search: **FE Access Errors** (`logs-fe-access-*`, `saved-search-id: fe-access-errors`)

## 3. Alert Kuralları

| Alert | Koşul | Süre | Aksiyon |
| --- | --- | --- | --- |
| **Access_Uptime_Low** | Synthetic check failure ratio > 5% | 10 dakika | PagerDuty “Access UI Down”, runbook §4’e bak |
| **Access_TTFA_Degraded** | `TTFA p95 > 8s` | 15 dakika | Slack `#mfe-alerts`; fallback flag değerlendirmesi |
| **Access_Mutation_Error** | Mutation success ratio < 94% | 5 dakika | OpsGenie “Access Mutations” → flag `access_mutation_write=false` |
| **Access_Client_Error_Spike** | Client error rate > 2 req/s | 5 dakika | Kibana log analizi, manifest hatası kontrolü |
| **Access_Audit_Link_Drop** | Audit link click rate sıfıra düşer (prod mesai saatleri içinde) | 30 dakika | Notification center / audit servis entegrasyonunu doğrula |

Alert rule dosyası: `observability/access-alerts.yaml` (Grafana Mimir). Değişiklikler Infrastructure repo PR’ı ile yapılır.

## 4. Runbook Entegrasyonu

- Insident sırasında `docs/04-operations/01-runbooks/52-mfe-access-runbook.md` §3 ve §4’e referans verin.  
- Telemetry grafikleri runbook checklist’inde (bakım işleri) aylık doğrulanır.  
- Alert bildirimi geldiğinde Slack mesajına runbook linki eklemek için OpsGenie integration ayarında “Custom URL” alanını güncelledik (`https://docs.example.com/mfe-access-runbook`).

## 5. To-do / Açık Noktalar

- [ ] Audit link click metriği için business hours filtreli yeni panel (opsiyonel).  
- [ ] TTFA paneline cihaz tipi (desktop/mobile) kırılımı eklenmesi.  
- [ ] Grafana screenshot otomatize edilip release notlarına eklenmesi (CI task).

> Sorular veya panel değişikliği talepleri için `#observability` Slack kanalını kullanın.

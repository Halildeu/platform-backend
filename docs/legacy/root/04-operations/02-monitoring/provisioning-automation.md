---
title: "Grafana Provisioning & Alert Routing Otomasyonu"
status: draft
owner: "@team/ops"
workflow_tickets:
  - OPS-02
last_review: 2025-11-12
---

Kapsam
- Reports/Export Guard dashboard + alert YAML dosyalarını ortam bazlı otomatik yükleme
- Slack/Email kanallarına alert yönlendirme

Kaynaklar
- Dashboard JSON: `docs/04-operations/02-monitoring/dashboards/*.json`
- Alert YAML: `scripts/perf/grafana/provisioning/alerting/*.yml`
 - Datasource YAML: `scripts/perf/grafana/provisioning/datasources/datasources.yml`
 - Contact Points: `scripts/perf/grafana/provisioning/alerting/contact-points.yml`
 - Notification Policies: `scripts/perf/grafana/provisioning/alerting/notification-policies.yml`
 - Audit Guard: `docs/04-operations/02-monitoring/dashboards/audit-guard-dashboard.json`, `scripts/perf/grafana/provisioning/alerting/audit-guard-*.yml`

Adımlar
1) Datasource UID’leri ve klasör isimlerini ortam bazlı şablonlayın (`datasources.yml` içinde `uid: loki/prometheus`).
2) Docker Compose/Helm’de provisioning path’lerini mount edin (dashboards/alerting/datasources klasörleri).
3) Slack/Email contact-point ve policy tanımlarını ekleyin (env: `GRAFANA_SLACK_WEBHOOK_URL`).

Kabul
- Stage/Prod’da provisioning otomatik
- Alert evaluation OK; routing doğru kanallara düşüyor

Doğrulama Komutları (CLI)
```bash
# Ortam değişkenlerini ayarlayın
export GRAFANA_URL="http://localhost:3010" # veya Stage/Prod
export GRAFANA_USER=admin
export GRAFANA_PASS=admin

# Hızlı kontrol
scripts/ops/grafana/check-reports-guard.sh

# Varsayılan receiver eksikse ekle (Slack yoksa e-posta kullanılır)
scripts/ops/grafana/set-default-receiver.sh
```

Örnek Çıktı
```
[grafana] datasources:
- Prometheus (uid=prometheus, type=prometheus)
- Loki (uid=loki, type=loki)
[grafana] rules (Operations / Reports Guard):
- Reports unauthorized ratio (warning) [uid=reports-unauthorized-warning]
- Reports unauthorized ratio (critical) [uid=reports-unauthorized-critical]
[grafana] active alerts (first 5):
- Reports unauthorized ratio (warning) => state=normal severity=warning
[grafana] done
```

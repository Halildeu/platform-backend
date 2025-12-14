---
title: "Audit Guard Observability (Loki/Prometheus)"
status: draft
owner: "@team/ops"
last_review: 2025-11-12
---

Amaç
- `/admin/audit/*` rotalarındaki guard akışlarını izlemek; unauthorized oranı ve path dağılımlarını görselleştirmek.

Kaynaklar
- Gateway logs (Loki): `app=api-gateway`, path `/admin/audit/` içeren kayıtlar
- Prometheus (opsiyonel): `route_guard_hits_total{path=~"/admin/audit/.*"}` sayaçları

Dashboard JSON
- `docs/04-operations/02-monitoring/dashboards/audit-guard-dashboard.json` (UID `OPS-AUDIT-GUARD-001`)

Loki Örnek Sorgular
- Hits by result (5m): `sum by (result) (rate({app="api-gateway"} |= "/admin/audit/" [5m]))`
- Unauthorized ratio: `(sum(rate({app="api-gateway"} |= "/admin/audit/" |= " 401 " [5m])))/(clamp_min(sum(rate({app="api-gateway"} |= "/admin/audit/" [5m])), 0.001))`

Alert Kuralları
- PromQL: `scripts/perf/grafana/provisioning/alerting/audit-guard-rules.yml`
- Loki: `scripts/perf/grafana/provisioning/alerting/audit-guard-loki-rules.yml`

Kabul
- Unauthorized ratio warning/critical eşiklerinde alert üretimi
- Dashboard panelleri veri üretiyor; datasources `loki`/`prometheus`


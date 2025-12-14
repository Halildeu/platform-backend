---
title: "Reports Guard Observability (Loki/Prometheus)"
status: draft
owner: "@team/ops"
last_review: 2025-11-12
---

Amaç
- `/admin/reports/*` rotalarındaki guard akışlarını (login redirect, unauthorized, success) izlemek; oranları ve trendleri dashboard’dan görünür kılmak.

Kaynaklar
- Gateway logs (Loki): `job=api-gateway` veya benzeri label’lı uygulama logları
- (Opsiyonel) Prometheus sayaçları: `route_guard_hits_total{path="/admin/reports/*", result="login|unauthorized|allowed"}` (gateway veya edge’de publish)

Dashboard JSON
- Provision dosyası: `docs/04-operations/02-monitoring/dashboards/reports-guard-dashboard.json` (UID: `OPS-REPORTS-GUARD-001`). Datasource UID’lerini ortamınıza göre `loki` olarak ayarlayın.

Loki Panel Örnekleri
1) Hits (toplam)
```
sum by (result) (
  rate(({app="api-gateway"} |~ "/admin/reports/" | json | line_format "{{.status}}" | unwrap status [5m]))
)
```
Alternatif (plain):
```
sum by (result) (
  count_over_time({app="api-gateway"} |= "/admin/reports/" [5m])
)
```

2) Unauthorized oranı (Loki):
```
(
  sum(rate({app="api-gateway"} |~ "/admin/reports/" |~ " 401 " [5m]))
) / clamp_min(
  sum(rate({app="api-gateway"} |~ "/admin/reports/" [5m])), 0.001)
```

Prometheus Panel Örnekleri (varsa sayaçlar)
1) Hits by result
```
sum by (result) (rate(route_guard_hits_total{path=~"/admin/reports/.*"}[5m]))
```
2) Unauthorized ratio
```
sum(rate(route_guard_hits_total{path=~"/admin/reports/.*",result="unauthorized"}[5m]))
/ clamp_min(sum(rate(route_guard_hits_total{path=~"/admin/reports/.*"}[5m])), 0.001)
```

Alarm Eşik Önerileri
- Unauthorized ratio > %5 (5 dk) → warning
- Unauthorized ratio > %15 (5 dk) → critical

Alert Provision
- YAML: `scripts/perf/grafana/provisioning/alerting/reports-guard-rules.yml` (Prometheus tabanlı örnek kurallar). Prometheus sayaçları yoksa Loki alertleriyle muadili oluşturun.
 - Loki alert kuralları: `scripts/perf/grafana/provisioning/alerting/reports-guard-loki-rules.yml` (Unauthorized ratio, 5m pencerede 0.05 / 0.15 eşikleri).
 - Contact points & policy: `scripts/perf/grafana/provisioning/alerting/contact-points.yml`, `notification-policies.yml` (Slack/Email routing). `GRAFANA_SLACK_WEBHOOK_URL` ortam değişkenini ayarlayın.

Ek Panel
- Dashboard’a "Top paths by result (last 5m)" tablo paneli eklendi. Loglarda `path` label’ı yoksa sorguyu `| json` veya `| logfmt` ile parse ederek uygun alan adını kullanın.

Uygulama Adımları
1) Dev/Lokal: Grafana’da yeni bir dashboard oluşturun, datasource olarak `loki` ve/veya `prometheus` seçin; yukarıdaki sorgularla 2–3 panel ekleyin.
2) Stage: Gateway log formatınız farklıysa Loki filterlarını uyarlayın (json/regex). Paneli `Operations / Reports Guard` klasörüne kaydedin.
3) Prod: Alarm kurallarını (Grafana unified alerting) ekleyin; Slack/Email kanallarına bağlayın.

Notlar
- Gateway log formatı uygulamadan uygulamaya değişebilir; `app`, `job`, `status`, `path` label/alan adlarını kendi ortamınıza göre uyarlayın.
- Eğer Prometheus sayaçları yayımlanmıyorsa yalnız Loki ile oran takibi yeterlidir.

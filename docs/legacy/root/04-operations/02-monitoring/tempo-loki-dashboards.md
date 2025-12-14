## Tempo/Loki Panoları ve Alarm Eşikleri

Amaç
- Trace (Tempo) ve log (Loki) panolarını standardize etmek; hata oranı/TTFA gibi metriklere alarm eşikleri belirlemek.

Önerilen Panolar
- FE Genel: TTFA, İlk render süresi, Error rate (%), Route bazında sayfa gösterimleri
- Audit/Access: işlem başına hata oranı, latency dağılımı (p50/p95)
- Security: 4xx/5xx dağılımı, rate-limit isabetleri, export istekleri (`users_export_rate_limit_total`, `users_export_audit_total`)
- CSV Export: `users_export_duration` histogramı, blocked/allowed oranı, `UserAuditEvent` sayıları
- Users Grid Search: `users_search_requests_total` (mode/search/filter tag’leri) ve `users_search_duration` (p50/p95/p99)

Alarm Eşikleri (örnek)
- Error rate (p95, 5 dk): > %1 → WARNING, > %2 → CRITICAL
- TTFA (sıcak, 100K dataset): > 3s → WARNING
- Export latency (p95): > 10s → WARNING

## Audit Events Filter Guardrail
- Kontrol edilen uç: `permission-service` üzerindeki `GET /api/audit/events` (AuditEventController).  
- Loki sorguları:
  - Toplam istek: `sum(rate({app="permission-service"} |= "GET /api/audit/events" [5m]))`
  - Filtre kullanan oran: `sum(rate({app="permission-service"} |= "filter[" [5m])) / clamp_min(sum(rate({app="permission-service"} |= "GET /api/audit/events" [5m])), 0.001)`
  - `id` shortcut gözlemi: `sum(rate({app="permission-service"} |= "id=" [5m]))`
- Alarm eşiği: Filtre kullanım oranı 5 dakika boyunca %20’nin altına düşerse WARNING, %10’un altına düşerse CRITICAL → guardrail pipeline’ın audit sorgularını tekrar gözden geçirmesini tetikler.
- Dashboard paneli: Tempo/Loki panosuna “Audit Filter Usage” paneli eklenir; datasource `loki`, panel UID `OPS-AUDIT-FILTER-001`.
- Alert tanımı `scripts/perf/grafana/provisioning/alerting/audit-filter-usage-loki.yml` dosyasında tutulur; Alertmanager etiketi `audit_filter_usage`.

Kabul Kriterleri
- Panolar üretim/QA’da erişilebilir; kritik metrikler için alert tanımlı.
- Telemetry köprüsü (SDK) ile gelen event’ler panolara düşer.

Bağlantılar
- `docs/04-operations/02-monitoring/otel-js-setup.md`
- `docs/01-architecture/01-system/02-frontend-architecture.md`

---
title: "Canary Pipeline Guardrail"
status: draft
owner: "@team/devops"
last_review: 2025-11-29
---

## 1. Amaç
Manifest tabanlı release’lerde canary adımlarını (10/50/100) otomatik olarak doğrulamak ve guardrail ihlalinde rollback tetiklemek. Bu doküman, `scripts/ci/canary/guardrail-check.mjs` script’i ile `.github/workflows/release-canary.yml` iş akışının nasıl çalıştığını özetler.

## 2. Guardrail Script’i
- Konum: `scripts/ci/canary/guardrail-check.mjs`
- Girdi: JSON metrics dosyası (`ttfb_p95_ms`, `error_rate_pct`, `sentry_error_rate_pct`, `audit_filter_usage_pct`, `timestamp`).
- Parametreler:
  - `--metrics` – Okunacak dosya yolu (varsayılan `scripts/ci/canary/canary-metrics.json`).
  - `--weight` – Canary traffic yüzdesi (log amaçlı).
  - `--ttfb`, `--error-rate`, `--sentry`, `--audit-filter-usage` – Opsiyonel eşik override değerleri.
- Çıkış: Metrikler eşik altındaysa 0, ihlalde 1. CI pipeline’da bu adım fail olursa rollback job’ları tetiklenir.

## 3. Metrics Toplama Script’i
- Konum: `scripts/ci/canary/pull-grafana-metrics.mjs`
- Amaç: Prometheus/Loki (veya Grafana proxy) sorgularından veriyi çekip `guardrail-check` ile uyumlu JSON çıktısı üretmek.
- Kullanım senaryoları:
  - **Sample modu:** `node pull-grafana-metrics.mjs --sample scripts/ci/canary/samples/stage10.json --output tmp-stage10.json`
  - **Prom/Loki modu:** `node pull-grafana-metrics.mjs --prom-url https://grafana/api/datasources/proxy/UID --api-key $GRAFANA_API_KEY --ttfb-query 'histogram_quantile(0.95, rate(app_ttfb_bucket[5m])) * 1000' --error-query '...' --sentry-query '...' --audit-filter-query '...' --audit-total-query '...' --output canary.json`
- Parametreler:
  - `--prom-url` taban URL’si (Grafana datasource proxy veya direkt Prometheus).
  - `--api-key` (opsiyonel) Grafana API anahtarı; `Bearer` öneki otomatik eklenir.
  - `--ttfb-query`, `--error-query`, `--sentry-query` – PromQL ifadeleri (scale varsayılanı sırasıyla 1ms / % / %).
  - `--audit-filter-query`, `--audit-total-query` – Audit endpoint çağrılarının log oranını hesaplamak için Loki/PromQL ifadeleri.
  - `--window-minutes` – raporlanan zaman penceresi (varsayılan 30).
- Çıktı: `ttfb_p95_ms`, `error_rate_pct`, `sentry_error_rate_pct`, `audit_filter_usage_pct`, `windowMinutes`, `timestamp`.

## 4. Workflow
- Dosya: `.github/workflows/release-canary.yml`
- Trigger: `workflow_dispatch`. Girdi parametreleri:
  - `mode`: `sample` (varsayılan) veya `live`. `sample` seçildiğinde repo içindeki örnek JSON’lar kullanılır.
  - `window_minutes`: `pull-grafana-metrics` script’ine iletilen pencere süresi.
  - Opsiyonel override alanları: `prom_url`, `ttfb_query`, `error_query`, `sentry_query`, `audit_filter_query`, `audit_total_query`. Boş bırakılırsa repo `vars/secrets` değerleri kullanılır.
- Job matrix üç adımı otomatik yürütür: 10% → 50% → 100% ağırlık. Her adımda:
  1. `pull-grafana-metrics.mjs` sample veya live modda çalıştırılır ve `scripts/ci/canary/generated-stage*.json` dosyası üretilir.
  2. `guardrail-check.mjs` aynı dosya üzerinden eşikleri doğrular. İhlalde job kırmızıya düşer.
  3. 10% ve 50% adımlarından sonra `sleep 3` ile gözlem penceresi simüle edilir (gerçek pipeline’da bu süre Argo CD tarafından yönetilecektir).
- **Live mod gereksinimleri:** Aşağıdaki Secrets/Variables tanımlı olmalı (workflow input’ları ile override edilebilir):
  - `secrets.CANARY_PROM_URL` veya `inputs.prom_url`
  - `secrets.GRAFANA_API_KEY`
  - `vars.CANARY_TTFB_QUERY`
  - `vars.CANARY_ERROR_QUERY`
  - `vars.CANARY_SENTRY_QUERY`
  - `vars.CANARY_AUDIT_FILTER_QUERY`
  - `vars.CANARY_AUDIT_TOTAL_QUERY`
- Bu değerler sağlandığında workflow’un “metrics (live)” adımı Grafana/Prometheus proxy’sinden gerçek metrikleri çekip guardrail’e besler; aksi halde sample modu kullanılmalıdır.

## 5. Rollback Senaryosu
- Workflow kırmızıya düşerse otomatik rollback job’ı tetiklenir (TODO: Argo CD step eklenir).
- Ops ekibi runbook: `docs/04-operations/01-runbooks/52-mfe-access-runbook.md` içindeki kill-switch bölümünü ve Unleash flag rehberini izler.

## 6. Yapılacaklar
- Canary workflow’unu Argo CD pipeline’ına bağlamak ve rollback job’larını eklemek.

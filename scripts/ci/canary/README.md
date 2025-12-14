## Canary Guardrail Script

Bu dizindeki `guardrail-check.mjs` script’i, canary aşamalarında kullanılan metriklerin eşik değerlerini kontrol eder. Varsayılan eşikler:

- `GUARDRAIL_TTFB_MS` (varsayılan 2000)
- `GUARDRAIL_ERROR_RATE` (varsayılan %2)
- `GUARDRAIL_SENTRY_RATE` (varsayılan %1)

Örnek:

```bash
node scripts/ci/canary/guardrail-check.mjs \
  --metrics scripts/ci/canary/samples/stage10.json \
  --weight 10
```

Eğer bir metrik eşik üstündeyse script `exit 1` döner ve pipeline rollback aksiyonuna geçer. Metrics dosyası JSON formatında `ttfb_p95_ms`, `error_rate_pct`, `sentry_error_rate_pct` alanlarını içerir.

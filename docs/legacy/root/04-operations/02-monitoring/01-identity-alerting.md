# Identity Health Alerting

Vault ve Keycloak sürekliliği güvenlik akışının kritik parçası. Aşağıdaki yapılandırmalar Grafana/Alertmanager’da minimum sürveyans için önerilir.

## 1. Vault Sağlık Metrikleri

Vault 1.13+ sürümleri `vault_status` ve `vault_core_unsealed` metriklerini Prometheus formatında sunar.

### Prometheus Scrape
```yaml
- job_name: vault
  metrics_path: /v1/sys/metrics
  params:
    format: [prometheus]
  authorization:
    credentials: ${VAULT_MONITORING_TOKEN}
  static_configs:
    - targets: ['vault-stage.internal:8200']
      labels:
        env: stage
```

### Alertmanager Kuralı
```yaml
groups:
  - name: vault-health
    rules:
      - alert: VaultSealed
        expr: vault_core_unsealed == 0
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "Vault sealed ({{ $labels.env }})"
          description: "No unsealed core detected for {{ $labels.env }}."
      - alert: VaultStandbyOnly
        expr: vault_status{ha_mode="standby"} == 1
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Vault running as standby-only ({{ $labels.env }})"
```

Grafana tarafında `vault_core_unsealed` ve `vault_status` metriklerini gösteren mini panel ekleyip alarm eşiği ile eşleştirin.

## 2. Keycloak Sağlık İzleme

Keycloak 24+ `/realms/<realm>/health/live` ve `/ready` endpoint’leri sağlıyor. Prometheus Blackbox Exporter ile izleyin:

```yaml
- job_name: keycloak-health
  metrics_path: /probe
  params:
    module: [http_2xx]
  static_configs:
    - targets:
        - https://keycloak-stage.example.com/realms/serban/health/ready
      labels:
        env: stage
  relabel_configs:
    - source_labels: [__address__]
      target_label: __param_target
    - target_label: instance
      replacement: keycloak-stage
    - target_label: __address__
      replacement: blackbox-exporter.observability:9115
```

Alert:
```yaml
- alert: KeycloakDown
  expr: probe_success{job="keycloak-health"} == 0
  for: 1m
  labels:
    severity: critical
  annotations:
    summary: "Keycloak health endpoint unreachable ({{ $labels.env }})"
    description: "Blackbox probe failed with {{ $labels.probe_http_status_code }}."
```

## 3. Env Smoke Workflow Sonuçları

GitHub Actions `Env Smoke` workflow’u (bkz. `docs/03-delivery/02-ci/03-env-smoke.md`) stage/prod sonrası tetikleniyor. Sonuçlarını Slack/PagerDuty’ye bağlamak için:

1. Environment → Secrets → `SLACK_WEBHOOK` ekleyin.
2. Workflow içine aşağıdaki adımı ekleyin:
   ```yaml
   - name: Slack notify
     if: ${{ failure() || success() }}
     uses: slackapi/slack-github-action@v1.27.0
     with:
       payload: |
         {
           "text": "Env Smoke ({{ github.event.inputs.target_env }}) ${{ job.status }}. Run: ${{ github.run_id }}"
         }
     env:
       SLACK_WEBHOOK_URL: ${{ secrets.SLACK_WEBHOOK }}
   ```
3. PagerDuty tarafında “Generic Webhook V3” entegrasyonunu açıp aynı pattern ile `Env Smoke` sonuçlarını olay olarak gönderin (success → resolve).

Bu üç katman (Vault metric alerts, Keycloak HTTP probe, Env Smoke workflow bildirimleri) kimlik altyapısındaki regrese risklerini dakikalar içinde saptayacak temel kapsama sağlayacaktır. 

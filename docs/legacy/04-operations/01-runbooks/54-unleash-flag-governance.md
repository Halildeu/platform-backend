---
title: "Unleash Flag Governance Runbook"
owner: "@team/platform-arch"
status: active
last_review: 2025-11-29
tags: ["feature-flags", "governance", "release-safety"]
---

# Amaç
Unleash feature flag’lerinin `{domain}:{feature}:{variant}` formatında, denetlenebilir ve geri izlenebilir biçimde yönetilmesini sağlar. Canary guardrail’leri (ADR-006) ve release safety story’si (E05-S01) bu runbook’taki checklist’lere dayanır.

# 1. Naming Standardı
| Segment  | Açıklama | Örnek Değerler |
|----------|----------|----------------|
| `domain` | İş alanı veya platform capability’si. Küçük harf, tire ile ayrılır. | `ag-grid`, `shell`, `manifest`, `security` |
| `feature` | Özellik veya modül adı. `snake_case` yerine tire kullanılır. | `inline-edit`, `manifest-sync`, `audit-pane` |
| `variant` | Flag tipi veya faz numarası (`v1`, `kill-switch`, `experiment`). | `v1`, `beta`, `kill-switch` |

- Tam kimlik: `{domain}:{feature}:{variant}` (örn. `ag-grid:inline-edit:v1`).  
- Kill-switch flag’lerine `:kill-switch` eki zorunludur (`shell:broadcast-channel:kill-switch`).  
- Ortam override notasyonu: `flag_id@env=default`. Örn. `ag-grid:inline-edit:v1@prod=false`.

# 2. Lifecycle & Checklist
| Aşama | Gerekenler | Sorumlu | Artefact |
|-------|------------|---------|----------|
| **Create** | Naming standardına göre kayıt, owner atanması, Backstage kartı açılması. | Feature Owner | Backstage `FeatureFlag` kaydı, runbook linki |
| **Canary** | Default `prod=false`, `stage=true`. Canary cohort listesi + guardrail ID’leri. | Platform Ops | Canary notu (`docs/03-delivery/02-ci/04-canary-pipeline.md`) |
| **GA** | Acceptance metrikleri yeşil, default tüm ortamlarda `true`. Cleanup tarihi planlanır. | Product Owner | Flag manifest güncellemesi |
| **Retire** | Kod referansları silinir, Unleash kaydı `archived`. Telemetry snapshot (kullanım/etki) rapora eklenir. | Squad + Security | Cleanup checklist, session-log kaydı |

- Tüm geçişler `docs/00-handbook/session-log.md` içine tarih ve sorumlu notu ile yazılır.  
- Stale flag limitleri: `<=10` aktif kill-switch, `<=5` expired canary.

# 3. Flag Manifest Şablonu
Manifest dosyası `docs/04-operations/assets/unleash/feature-flags.yaml` altında tutulur (repo içinde yoksa aynı dizin oluşturulmalıdır). Backstage/Argo CD pipeline’ı bu dosyayı single source kabul eder.

```yaml
apiVersion: backstage.io/v1alpha1
kind: FeatureFlag
metadata:
  name: ag-grid-inline-edit-v1
  annotations:
    mp/flag-id: ag-grid:inline-edit:v1
    mp/flag-owner: team/grid
    mp/flag-lifecycle: canary
    mp/flag-created: "2025-11-29"
spec:
  description: >
    Enables inline edit toolbar on AG Grid entity screens. Default prod=false until
    guardrail metrics (TTFA<2s, error rate <1%) stay green for 48h.
  environments:
    - name: dev
      default: true
      rollout: 100
    - name: stage
      default: true
      rollout: 50
    - name: prod
      default: false
      rollout: 10
  tags:
    - canary
    - security-critical
  killSwitch:
    linkedFlag: ag-grid:inline-edit:kill-switch
    runbook: docs/04-operations/01-runbooks/43-kill-switch-plan.md
```

# 4. Backstage & Telemetry
- Backstage UI `FeatureFlag` entity listesi `mp/flag-id` annotation’ına göre filtrelenir.
- Guardrail ihlallerinde `scripts/ci/canary/guardrail-check.mjs` pipeline flag ID’sini `statusContext` alanında taşır.
- Telemetry olayları (Grafana/Loki) `flag_id`, `actor`, `environment`, `decision` alanlarını zorunlu taşır; metadata `jsonPayload.flag_context`.

# 5. Cleanup & Kill-Switch
1. Flag GA olduktan sonra 14 gün içinde koddan çıkarılır; issue linki manifest’e yazılır (`mp/flag-retire-task` annotation).  
2. Kill-switch flag’leri `prod` ortamında `false` default ile kalır; incident sırasında `true` yapılır ve runbook 43 izlenir.  
3. Cleanup tamamlandığında `feature-flags.yaml` satırı silinir, Unleash kaydı `archived`, session-log’a “Flag retired” notu eklenir.

# 6. Haftalık Raporlama
- `scripts/ci/security` pipeline raporları `security-reports` artefact’ı içinde `flag-health.json` dosyasını bekler. Flag manifest runbook’a uyumlu olduğunda `npm run flags:report` (frontend repo) output’u bu dosyaya kopyalanır.
- Rapor kolonları: `flag_id`, `owner`, `lifecycle`, `age_days`, `stale?`, `next_action`.
- Security + Platform ekipleri pazartesi stand-up’ında stale > 10 ise cleanup planı açar.
- Otomasyon: `.github/workflows/security-guardrails.yml` içindeki `export-flag-health.rb` script’i manifesti okuyup `test-results/security/flag-health.json` raporunu üretir; gerektiğinde `scripts/ci/security/export-flag-health.rb` manuel çalıştırılarak aynı çıktı elde edilebilir.

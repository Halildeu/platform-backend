# Epic E05 – Release Safety & Security Pipeline

- Epic Priority: 500  
- Durum: In Progress

## Açıklama

Canary yayın, feature flag yönetişimi, DR/HA & edge stratejisi ve CI/CD güvenlik zincirini kapsar. Amaç, manifest tabanlı platformu güvenli ve geri alınabilir şekilde yayınlamak ve guardrail metrikleriyle korumaktır.

EPIC_BUSINESS_CONTEXT:
- Kullanıcı etkisini minimize eden kademeli rollout ve hızlı rollback.
- Güvenlik taramaları ve supply‑chain imzaları ile güvenilir release zinciri.
- DR/HA ve edge konfigürasyonları ile platformun kesintisiz çalışması.

## Fonksiyonel Kapsam

- Argo CD/Harbor ile canary rollout adımları ve guardrail’ler.
- Unleash feature flag yaşam döngüsü ve kill‑switch akışları.
- SAST/DAST, dependency taraması, SBOM ve imzalı artefact üretimi.
- DR/HA ve edge katmanı konfigürasyonlarının (manifest/sözlük) release akışına bağlanması.

## Non-Functional Requirements (Epic Seviyesi)

- Canary guardrail ihlallerinde rollback hedefi < 5 dakika.
- SAST/DAST adımlarında kritik bulgular kapanmadan release olmaması.
- DR drill’lerinde manifest/sözlük servislerinin kesintisiz kalması.

## Story Listesi

| Story ID | Story Adı                                  | Durum    | Story Dokümanı                                 |
|----------|--------------------------------------------|---------|-----------------------------------------------|
| E05-S01  | Canary, Flag & DR Guardrail’leri v1.0      | Planned | 02-stories/E05-S01-Release-Safety-and-DR.md   |

## Doküman Zinciri (Traceability)

- Epic: `docs/05-governance/01-epics/E05_ReleaseSafetyAndSecurityPipeline.md`
- Story:
  - `docs/05-governance/02-stories/E05-S01-Release-Safety-and-DR.md`
- SPEC:
  - `docs/05-governance/06-specs/SPEC-E05-S01-RELEASE-SAFETY-V1.md`
- Acceptance:
  - `docs/05-governance/07-acceptance/E05-S01-Release-Safety-and-DR.acceptance.md`
- ADR:
  - `docs/05-governance/05-adr/ADR-006-canary-and-rollback-strategy.md`
  - `docs/05-governance/05-adr/ADR-009-feature-flag-governance.md`
  - `docs/05-governance/05-adr/ADR-010-security-pipeline.md`
  - `docs/05-governance/05-adr/ADR-013-dr-ha-and-edge-strategy.md`

## Story–Sprint Eşleştirmeleri

| Story ID | Sprint ID | Not                                                    |
|----------|-----------|--------------------------------------------------------|
| E05-S01  | (TBD)     | Canary, feature flag ve DR guardrail’lerinin ilk dalgası |

## Bağımlılıklar

- Manifest platformu (E04).
- Unleash ve Argo CD/Harbor altyapısı.
- Güvenlik ekibi ve ops ekiplerinin süreçleri.

## Riskler

- Guardrail metriklerinin eksik veya flaky olması; canary kontrolsüzleşmesi.
- Güvenlik taramalarının pipeline’ı aşırı yavaşlatması ve devre dışı bırakılması.

## İlgili Artefaktlar

- `docs/05-governance/05-adr/ADR-006-canary-and-rollback-strategy.md`
- `docs/05-governance/05-adr/ADR-009-feature-flag-governance.md`
- `docs/05-governance/05-adr/ADR-010-security-pipeline.md`
- `docs/05-governance/05-adr/ADR-013-dr-ha-and-edge-strategy.md`

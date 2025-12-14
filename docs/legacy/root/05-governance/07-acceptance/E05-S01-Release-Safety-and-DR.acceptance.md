---
title: "Acceptance — E05-S01 Release Safety & DR"
story_id: E05-S01
status: in-review
owner: "@team/platform-arch"
last_review: 2025-12-06
modules:
  - ops-ci
  - platform-ops
  - frontend-shell
  - api-gateway
  - observability
---

# 1. Amaç
Canary/flag/DR guardrail zincirinin prod/test ortamlarında eksiksiz devreye alındığını, CI güvenlik pipeline’ının bloklayıcı şekilde çalıştığını ve DR drill’lerinin runbook’larda kayıtlı olduğunu doğrulamak.

# 2. Traceability (Bağlantılar)
- **Epic:** `docs/05-governance/01-epics/E05_ReleaseSafetyAndSecurityPipeline.md`
- **Story:** `docs/05-governance/02-stories/E05-S01-Release-Safety-and-DR.md`
- **Spec:** `docs/05-governance/06-specs/SPEC-E05-S01-RELEASE-SAFETY-V1.md`
- **ADR:** ADR-006, ADR-009, ADR-010, ADR-013
- **Runbook:** `docs/04-operations/01-runbooks/54-unleash-flag-governance.md`, `docs/04-operations/01-runbooks/vault-failfast-fallback.md`
- **PROJECT_FLOW:** E05-S01 satırı

# 3. Kapsam (Scope)
Canary adımları (10/50/100), guardrail metrikleri, Unleash flag lifecycle, CI security pipeline (SAST/DAST/dependency/SBOM), DR/HA drill’leri ve ilgili dokümantasyon güncellemeleri.

> Modül listesi ve aşağıdaki başlıklar AGENT-CODEX §6.3.1 “Nerelere değecek?” adımının kaydıdır; boş bırakılamaz.

# 4. Acceptance Kriterleri (Kontrol Listesi)

### Ops / CI
- [ ] `security-guardrails.yml` workflow’u SpotBugs, Dependency-Check (NVD API key ile), ZAP baseline ve SBOM + cosign imza adımlarını çalıştırır; kritik bulgularda pipeline kırmızı kalır.  
  - Durum: Workflow taslağı repo’ya eklendi (`.github/workflows/security-guardrails.yml`), adımlar placeholder; gerçek tarama araçları ve secret’lar bağlanınca koşacak.
- [ ] `release-canary.yml` 10/50/100 weight adımlarını çalıştırır ve guardrail script’i gerçek Grafana/Sentry API’lerinden veri çekerek eşik ihlalinde rollback tetikler.  
  - Durum: Workflow taslağı eklendi (`.github/workflows/release-canary.yml`), guardrail kontrolü için placeholder script (`backend/scripts/guardrails/check-metrics.mjs`). ArgoCD/Harbor ve gerçek metrik API entegrasyonu bekliyor.
- [ ] `flag-health.json` artefact’ı ve session-log girdileri güncel; PROJECT_FLOW’daki notta son koşu tarihleri belirtilmiştir.

### Platform Ops / DR
- [ ] DR drill’i en az bir kez gerçekleştirilmiş, manifest/sözlük servisleri için failover < 1 dk içinde tamamlanmış ve runbook + session-log’a kaydedilmiştir.  
- [ ] Unleash flag manifesti (`docs/04-operations/assets/unleash/feature-flags.yaml`) ve runbook 54 gereği kill-switch flag’leri planlı durumdadır; canary/GA geçişleri kayıtlıdır.  
- [ ] Rollback akışı (Argo CD eski revizyona dönme + kill-switch tetikleme) manuel prova ile doğrulanmış, kanıtı acceptance’e bağlanmıştır.

### Frontend Shell / API Gateway / Observability
- [ ] Canary aşamalarında shell + gateway logları TTFA, hata oranı ve Sentry error metriklerini toplayıp guardrail script’in kullanacağı endpoint’lere yayınlar.  
- [ ] Frontend shell kill-switch flag’leri (`shell:broadcast-channel:kill-switch` vb.) dokümante edilen lifecycle’a göre çalışmakta; trigger edildiğinde UI maintenance mesajına geçmektedir.  
- [ ] Gateway 401/403 log’ları ve Prometheus/Grafana dashboard’ları canary guardrail panelinde görünür; ihlal test senaryosu ile alarm üretildiği kanıtlanmıştır.

# 5. Test Kanıtları (Evidence)
- Actions run linkleri (`release-canary`, `security-guardrails`)  
- Grafana/Sentry ekran görüntüleri ve guardrail JSON çıktı örnekleri  
- DR drill raporu + session-log satırı  
- Unleash manifest diff’i ve kill-switch tetikleme logu

# 6. Sonuç
Genel Durum: in-review (pipeline taslakları eklendi, ortam entegrasyonu ve drill/alarmlar bekleniyor).  
Tüm modül başlıkları checklist’lerini sağladığında Story PROJECT_FLOW’da ✔ Done’a çekilecek ve session-log’a kapanış kaydı eklenecektir.

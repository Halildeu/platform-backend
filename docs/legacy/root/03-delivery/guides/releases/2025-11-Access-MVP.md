---
title: "Release Playbook - Access Module MVP"
status: draft
owner: "@team/platform-fe"
release_window: 2025-11-10
---

# 1. Ön Hazırlık
- [ ] Roadmap / changelog güncellendi (`docs/05-governance/PROJECT_FLOW.md`, ilgili `01-epics/` ve `02-stories/` dosyaları)
- [ ] Özellik bayrakları doğrulandı:
  - `access_mutation_write = true`
  - `access_grid_lazy_load = true`
  - `audit_deeplink_enabled = true`
- [ ] TMS sözlükleri çekildi (`npm run i18n:pull`)
- [ ] `i18n-smoke.yml` pseudolocale pipeline yeşil

# 2. Test & Doğrulama
- [ ] `npm run test --prefix frontend/apps/mfe-access`
- [ ] `npm run test --prefix packages/i18n-dicts`
- [ ] `npm run i18n:pseudo`
- [ ] `npm run build:shell`
- [ ] `npm run security:sri:check`
- [ ] `docs/04-operations/01-runbooks/52-mfe-access-runbook.md` güncel

# 3. Release Adımları
- [ ] `npm run build --prefix frontend/apps/mfe-access`
- [ ] Artefact publish: Argo CD pipeline `mfe-access` → `access-mvp-2025-11`
- [ ] Config/secrets: Vault path `kv/platform/access` kontrolü

# 4. Yayın Sonrası
- [ ] Prod smoke: Shell Access sayfası TTFA < 5s, grid yükleniyor
- [ ] Notification center audit link test edildi
- [ ] Grafana Access dashboard 2 saat izleme (TTFA, mutation success, error rate)
- [ ] Release notu `docs/03-delivery/guides/releases/notes/access-mvp.md` → Slack `#release-updates`

# 5. Geri Dönüş (Rollback) Planı
- [ ] Argo CD → `mfe-access` geçmiş deploy rollback
- [ ] Feature flag fallback: `access_mutation_write=false`, `access_grid_lazy_load=false`
- [ ] Manifest SRI revert (`security/sri-manifest.json` önceki hash)

# 6. Lessons & Follow-up
- [ ] Telemetry olayları (audit link tıklanma, mutation success) raporlandı mı?
- [ ] `mfe-audit` deep-link POC için flow 3 hazırlığı

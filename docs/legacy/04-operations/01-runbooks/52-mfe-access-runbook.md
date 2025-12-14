---
title: "mfe-access Runbook"
status: draft
owner: "@team/platform-fe"
last_review: 2025-11-03
service: "mfe-access"
sla: "99.5%"
---

# 1. Servis Özeti
- **Amaç:** `mfe-access` manifest tabanlı rol & policy yönetim arayüzünü sunar; erişim matrisini `permission-service` üstünden günceller, audit ID üretip shell notification center’a iletir.
- **Sorumlu ekip:** Platform FE + Access domain temsilcisi. Escalation: `#mfe-oncall` → `@team/platform-fe` → `@team/platform-arch`.
- **Prod URL:** `https://shell.example.com/access/*`  
  **Stage URL:** `https://shell-stage.example.com/access/*`
- **Dashboards:** Grafana → *Access Module Overview* (`grafana.example.com/d/fe-access/access-overview`) / Kibana log index `logs-fe-access-*`.
- **RunCommand:** `npm run start --prefix frontend/apps/mfe-access` (local), manifest shell enpoint `https://shell.example.com/manifest/access/roles`.

# 2. SLA / SLO / Error Budget
- **SLA:** 99.5% erişilebilirlik. Uptime kaynağı: Grafana Synthetic check `access-uptime`.
- **SLO:**  
  - TTFA < 5s (p95) — kaynağı: RUM telemetry `fe.access.ttfa`.  
  - Mutation success rate ≥ 98% — kaynağı: OTEL metric `fe.access.mutation_success_ratio`.
- **Alert Thresholds:**  
  - Uptime < 99.5% → PagerDuty `Access UI Down`.  
  - TTFA p95 > 8s (15 dk üst üste) → Slack `#mfe-alerts`.  
  - Mutation success < 94% (5 dk) → OpsGenie `Access Mutations`.

# 3. Monitoring
- **Grafana panosu:** *Access Module Overview* kartları: TTFA, mutation ratio, JS error count, grid fetch latency.  
  Kritik metrikler:  
  - `fe.access.ttfa_p95` < 8s  
  - `fe.access.grid_fetch_latency_p95` < 2.5s  
  - `fe.access.client_error_rate` < %2
- **Log aramaları:** Kibana index `logs-fe-access-*`, Kuery örneği:  
  - `service.name:fe-access AND level:error`  
  - `message:"auditId" AND level:warn` (audit entegrasyon sorunları)
- **Alerts:** Grafana contact point *MFE-Squad*; OpsGenie policy *Access-UI*.

# 4. Incident Prosedürü
## 4.1 İlk Adımlar (Triage Checklist)
1. Grafana uptime ve TTFA panolarına bak; koyu kırmızı mı?  
2. Kibana’da son 15 dakikadaki error log’larını filtrele (`grid_fetch_error`, `mutation_failed`).  
3. Notification center backlog’unda (shell) “Audit ID” alanı boş mu? → audit servis problemini gösterir.  
4. Backend health: `permission-service`, `user-service`, `auth-service` `/actuator/health` uçları.

## 4.2 Sık Görülen Alarmlar
- **Manifest fetch 404/403:** Shell manifest CDN/SRI problemi. Aksiyon: `npm run security:sri:check` (root), hatalı manifest için SRI dosyasını güncelle (bkz. `security/sri-manifest.json`), `mfe-shell` deploy.  
- **Grid fetch timeout:** `permission-service` yavaş. Aksiyon: Backend oncall’a haber ver, `PROMQL: permission_grid_query_duration_ms` incele. FE tarafında fallback varyantını (read-only view) Feature Flag `access_grid_readonly` ile aç.  
- **Mutation failure spike:** Audit ID dönmüyor. Step: Feature flag `access_mutation_write` off → UI’yı read-only moda al, kullanıcıya banner göster (flag handle `frontend/apps/mfe-access/src/app/featureFlags.ts`).  
- **Auth 401 storm:** Shell token expired. Aksiyon: `auth-service` token provider’a bak, shell broadcast channel log’larını kontrol et.

## 4.3 Rollback / Disable
- **Feature flag fallback:** Unleash flag `access_mutation_write` false → grid read-only.  
- **Deploy rollback:** Argo CD `mfe-access` application `argocd.example.com` üstünden son stable revision’a dön.  
- **Hard stop:** CDN’den manifest’i disable et (`manifest-platform` paneli → `access-roles` manifest). Kullanıcılara status sayfasından duyuru gönder.

# 5. Bağımlılıklar
- **Yukarı akış:**  
  - `permission-service` (REST `/api/permissions/**`).  
  - `user-service` (rol meta verisi).  
  - `auth-service` (JWT / servis token).  
  - `notification-service` (opsiyonel; audit link bildirimleri).  
- **Aşağı akış:**  
  - `mfe-audit` (deep link highlight).  
  - `shell notification center`.  
- **Config / Flag:** `access_mutation_write`, `access_grid_lazy_load`, `audit_deeplink_enabled`.

# 6. Bakım İşleri
- **Aylık:**  
  - Manifest SRI doğrula (`npm run security:sri:check`).  
  - Feature flag envanteri gözden geçir, stale flag’leri kapat (`docs/05-governance/05-adr/ADR-009-feature-flag-governance.md`).  
- **Düzenli:** Telemetry event şeması (`fe.access.*`) ile Grafana dashboard senkronizasyonu.  
- **Çeyreklik:** Audit ID deep-link çalışmasını manuel test et (`frontend/apps/mfe-access/src/widgets/access-management/ui/AccessGrid.ui.tsx`).
- **Runbook doğrulama:** Doc hygiene toplantısında check (status: draft → published hedefi).

# 7. Ekler
- **Referans:** `frontend/docs/01-architecture/03-routing/` (FE dokümanları), ilgili Epic/Story kaydı için `docs/05-governance/PROJECT_FLOW.md` ve `01-epics/` / `02-stories/` altındaki dokümanlar.  
- **Postmortem linkleri:** Yok (ilk sürüm).  
- **Not:** Incident sırasında alınan ekran görüntüleri `confluence/fe-access/incidents` dizinine yüklenmeli.

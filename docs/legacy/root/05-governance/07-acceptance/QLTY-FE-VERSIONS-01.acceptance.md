---
title: "ACCEPTANCE – QLTY-FE-VERSIONS-01"
story_id: QLTY-FE-VERSIONS-01
status: draft
owner: "@team/frontend-arch"
last_review: 2025-11-22
---

# 1. Amaç
Kritik FE bağımlılıklarının (react-router, react-router-dom, @tanstack/react-query, devtools/cli) versiyonlarının host/remote ve MF shared listelerinde hizalı olduğunu doğrulamak.

# 2. Traceability (Bağlantılar)
- Story: `docs/05-governance/02-stories/QLTY-FE-VERSIONS-01-Frontend-Version-Pinning.md`
- ARCH: `frontend/docs/01-architecture/01-shell/01-frontend-architecture.md`
- STYLE: `docs/00-handbook/STYLE-FE-001.md`
- PROJECT_FLOW: QLTY-FE-VERSIONS-01

# 3. Kapsam (Scope)
package.json ve MF shared config audit + drift tespiti; otomatik kontrol veya smoke test.

# 4. Kabul Kriterleri
1. Shell ve tüm remote MFE’lerde router/query/devtools versiyonları pin’li ve aynı sürümdedir; package.json ile MF shared uyumludur.  
2. Drift tespit eden bir betik veya smoke test CI’da çalışır ve rapor verir.  
3. ARCH dokümanı versiyon pin kuralını ve kontrol mekanizmasını içerir.  
4. PROJECT_FLOW ve session-log güncellenmiştir.  
5. Build/lint/test tüm MFE’lerde yeşildir; drift bulunursa fail olur.  
6. Dev/prod profilleri arasında versiyon drift’i yoktur.  
7. MF shared listesi router/query devtools için singleton/pin durumunu açıkça belirtir.

# 5. Notlar
- Router pin uygulaması QLTY-MF-ROUTER-01 ile örtüşür; bu acceptance versiyon tarafını doğrular.

---
title: "ACCEPTANCE – QLTY-MF-ROUTER-01"
story_id: QLTY-MF-ROUTER-01
status: draft
owner: "@team/frontend-arch"
last_review: 2025-11-22
---

# 1. Amaç
Bu doküman, QLTY-MF-ROUTER-01 için kabul kriterlerini tanımlar.

# 2. Traceability (Bağlantılar)
- Story: `docs/05-governance/02-stories/QLTY-MF-ROUTER-01-Frontend-Router-Shared.md`
- ARCH: `frontend/docs/01-architecture/01-shell/01-frontend-architecture.md`
- STYLE: `docs/00-handbook/STYLE-FE-001.md`
- PROJECT_FLOW: QLTY-MF-ROUTER-01

# 3. Kapsam (Scope)
Router bağımlılıklarının MF shared + package.json seviyesinde tek kopya/singleton ve pin’li olması; drift’in testlerle yakalanması.

# 4. Kabul Kriterleri
1. Shell ve tüm remote MFE’lerin MF shared listesinde `react-router` ve `react-router-dom` singleton + aynı versiyonla tanımlıdır.  
2. package.json bağımlılık versiyonları MF shared ile uyumludur; drift yoktur.  
3. Playwright veya MF smoke testi router drift/çift kopya tespitini doğrular (bkz. `tests/playwright/router-smoke.spec.ts`, RUN_MF_ROUTER_SMOKE=true ile çalışır).  
4. ARCH dokümanı v1 router paylaşım kuralını ve pin gereksinimini içerir.  
5. PROJECT_FLOW ve session-log QLTY-MF-ROUTER-01 için Review/Done aşamasına taşınmıştır.  
6. CI’de router drift kontrolü eklenmiş ve yeşil geçmiştir.  
7. Dev/prod profillerinde router paylaşımı aynı davranışı verir (çift kopya uyarısı yoktur).

# 5. Notlar
- Versiyon pin denetimi QLTY-FE-VERSIONS-01 ile birlikte tutulabilir; bu acceptance router odaklı kontrolleri kapsar.

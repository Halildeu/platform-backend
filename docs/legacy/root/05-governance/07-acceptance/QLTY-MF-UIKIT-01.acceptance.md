---
title: "ACCEPTANCE – QLTY-MF-UIKIT-01"
story_id: QLTY-MF-UIKIT-01
status: draft
owner: "@team/frontend-arch"
last_review: 2025-11-22
---

# 1. Amaç
UI Kit tüketim modelinin tekilleştirilip tüm MF ve dokümanlarda hizalandığını doğrulamak.

# 2. Traceability (Bağlantılar)
- Story: `docs/05-governance/02-stories/QLTY-MF-UIKIT-01-Frontend-UI-Kit-Model.md`
- ARCH: `frontend/docs/01-architecture/01-shell/01-frontend-architecture.md`
- STYLE: `docs/00-handbook/STYLE-FE-001.md`
- PROJECT_FLOW: QLTY-MF-UIKIT-01

# 3. Kapsam (Scope)
UI Kit’in paket veya MF remote olarak tek model seçilmesi; import örnekleri, MF config ve dokümanların buna göre hizalanması.

# 4. Kabul Kriterleri
1. Resmi UI Kit modeli (paket/remote veya hibrit) seçilmiş ve ARCH’ta belirtilmiştir.  
2. Tüm örnek kodlar ve import path’leri seçilen modele göre güncellenmiştir; karışık kullanım kalmamıştır.  
3. MF config (remote tanımı/shared) seçilen modelle uyumludur; build/lint/test yeşildir.  
4. ui-kit grid/tasarım helper’ları seçilen modelde çalışır; runtime hata yoktur.  
5. PROJECT_FLOW ve session-log Story için güncellenmiştir.  
6. STYLE-FE-001’e uygunluk korunmuştur; ErrorBoundary import örnekleri seçilen modele göre tekilleştirilmiştir.

# 5. Notlar
- Versiyon pin kontrolleri QLTY-FE-VERSIONS-01 ile birlikte takip edilecektir.

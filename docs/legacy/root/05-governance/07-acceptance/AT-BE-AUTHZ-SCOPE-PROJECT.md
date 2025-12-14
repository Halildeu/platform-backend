---
title: "ACCEPTANCE – QLTY-BE-AUTHZ-SCOPE-03-PROJECT"
story_id: QLTY-BE-AUTHZ-SCOPE-03-PROJECT
status: draft
owner: "@halil"
last_review: 2025-12-03
modules:
  - project-service
  - permission-service
  - ops-ci
---

# 1. Amaç
`QLTY-BE-AUTHZ-SCOPE-03-PROJECT` tesliminin tamamlanmış sayılabilmesi için gerekli kabul kriterlerini tanımlar. Acceptance, PROJECT_FLOW’daki “✔ Tamamlandı” geçişinin tek resmi kaynağıdır.

# 2. Traceability (Bağlantılar)
- **Story:** `docs/05-governance/02-stories/QLTY-BE-AUTHZ-SCOPE-03-PROJECT.md`
- **SPEC:** (yok; mevcut scope spesifikasyonuna dayanıyor)
- **ADR:** `docs/05-governance/05-adr/ADR-AUTHZ-SCOPE-01.md`
- **API:** (varsayılan proje API dokümanı; özel ek yok)
- **PROJECT_FLOW:** QLTY-BE-AUTHZ-SCOPE-03-PROJECT satırı

# 3. Kapsam (Scope)
Bu acceptance aşağıdaki alanları kapsar:
1. Fonksiyonel davranış: proje liste/arama uçlarında PROJECT scope filtrasyonu  
2. API uyumluluğu: mevcut response zarfı/pagination ile uyum  
3. UI/UX/i18n: (bu Story’de kapsam dışı)  
4. Güvenlik & Yetkilendirme: /authz/me tüketimi, permissions/superAdmin parse, scope boş → 200+empty  
5. Performans/Dayanıklılık: IN filtresi index’i kullanır, mevcut bütçeyi aşmaz; CI guardrails yeşil

# 4. Acceptance Kriterleri (Kontrol Listesi)

# project-service
- [ ] Fonksiyonel: superAdmin ile çağrıldığında tüm projeler listelenir.
- [ ] Fonksiyonel: PROJECT scope’u [P1, P2] olan kullanıcı yalnız P1/P2 projelerini görür.
- [ ] Fonksiyonel/Güvenlik: PROJECT scope’u boş olan kullanıcı 200 + boş liste alır (403 değil).
- [ ] Güvenlik: /authz/me yanıtındaki permissions + superAdmin alanları doğru parse edilir; scope/izin eksikse güvenli hata (401/403) verilir.
- [ ] Doğrulama: repository sorgusu `projectId IN (:allowedProjectIds)` filtresini uygular; IN listesi boşsa sorgu çalıştırılmaz.

# permission-service
- [ ] /authz/me yanıtında scopeType=PROJECT için allowedScopes alanı bulunur ve project-service ile uyumludur.
- [ ] Kontrat bozulmamıştır: permissions + allowedScopes + superAdmin alanları mevcut servislerle aynı şemadadır.
- [ ] RS256 + audience guardrail değişmemiştir; yeni ek alanlar doğrulama akışını etkilemez.

# ops-ci
- [ ] CI/guardrail pipeline (lint, dependency scan vb.) project-service değişiklikleriyle yeşildir.
- [ ] Runbook/PROJECT_FLOW güncellenmiş, kapanışta session-log’a not düşülecektir.
- [ ] IN filtresi için index kullanımı teyit edilmiş; performans bütçesini aşan sorgu yok.

# 5. Test Kanıtları
- [ ] Service test çıktıları (admin vs scoped vs scope boş)  
- [ ] Gerçek token ile proje listesi çağrısı (admin tam liste, scoped user kısıtlı liste)  
- [ ] Log/traceId kanıtı; /authz/me yanıtı maskeli örnek

# 6. Sonuç
Genel Durum: draft | review | ✔ done  
Tüm maddeler karşılandığında PROJECT_FLOW’da Story “✔ Tamamlandı” durumuna alınır ve session-log’a kapanış kaydı eklenir.

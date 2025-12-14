# MP-ACCEPTANCE-FORMAT.md (Resmî Şablon)

Bu şablon MP-STORY / MP-SPEC / MP-ADR zinciriyle uyumlu, PROJECT_FLOW “✔ Tamamlandı” durumunu belirleyen tek resmi acceptance formatıdır.

---
title: "ACCEPTANCE – <Story Başlığı>"
story_id: <STORY-ID>
status: draft
owner: "@halil"
last_review: YYYY-MM-DD
modules:
  - frontend-shell
  - api-gateway
  - ops-ci
---

# 1. Amaç
Bu doküman, `<STORY-ID>` için teslimatın tamamlanmış sayılabilmesi adına gerekli kabul kriterlerini tanımlar.  
Acceptance tüm geliştirmelerin doğrulandığı TEK resmi kaynaktır ve PROJECT_FLOW’daki “✔ Tamamlandı” durumuna geçişi belirler.

---

# 2. Traceability (Bağlantılar)

- **Epic:** `docs/05-governance/01-epics/<EPIC-ID>.md`
- **Story:** `docs/05-governance/02-stories/<STORY-ID>.md`
- **Spec:** `docs/05-governance/06-specs/<SPEC-ID>.md`
- **ADR:** `docs/05-governance/05-adr/<ADR-ID>.md`
- **API:** (varsa) `docs/03-delivery/api/<domain>.api.md`
- **PROJECT_FLOW:** `<STORY-ID>` satırı

> Not: Acceptance dosyası, STORY ve SPEC ile çift yönlü ilişki kurar.

---

# 3. Kapsam (Scope)
Bu acceptance aşağıdaki alanları kapsar:

1. Fonksiyonel davranış  
2. API uyumluluğu  
3. UI/UX, i18n ve erişilebilirlik  
4. Güvenlik & Yetkilendirme  
5. Performans, dayanıklılık ve limit testleri  

> Front matter’daki `modules` listesi + aşağıdaki modül başlıkları AGENT-CODEX §6.3.1 zincirindeki “Nerelere değecek?” adımının kaydıdır; yeni acceptance oluştururken boş bırakılamaz.

---

# 4. Acceptance Kriterleri (Kontrol Listesi)

Acceptance maddeleri modül/servis başlıkları altında gruplanır; her modül için en az bir fonksiyonel, bir güvenlik ve bir doğrulama kriteri yazmak zorunludur. Örnek:

### Frontend Shell
- [ ] Story’de tanımlı ana fonksiyon eksiksiz çalışmaktadır; error/empty/loading akışları SPEC ile uyumludur.  
- [ ] Tüm kullanıcı metinleri i18n sözlüklerinden çözülür; pseudo-locale ve A11y smoke testleri geçer.  
- [ ] Responsive davranış en az 3 breakpoint’te doğrulanmış, tema/appearance/density aksları bozulmamıştır.

### API Gateway
- [ ] Endpoint path/method/query değerleri STYLE-API-001 ile uyumludur; pagination/sorting/advancedFilter kombinasyonları test edilmiştir.  
- [ ] Response zarfı (`items`, `total`, `page`, `pageSize`) ve hata gövdesi (`error`, `message`, `fieldErrors`, `meta.traceId`) standartla uyumludur.  
- [ ] JWT / servis token doğrulamaları ve rate-limit/429 korumaları SPEC + ADR kararlarına göre çalışmaktadır.

### Ops / CI
- [ ] security-guardrails pipeline’ı ilgili komutları (lint, dependency scan, SBOM, DAST vb.) çalıştırıp yeşil dönmektedir.  
- [ ] Performans limitleri (TTFA, AG Grid SSRM coalescing, export süreleri vb.) kabul edilen bütçe içinde raporlanmıştır; gereksiz network isteği yoktur.  
- [ ] Runbook ve monitoring/grafik kayıtları güncellenmiş, rollback adımları doğrulanmıştır.

---

# 5. Test Kanıtları (Evidence)
Aşağıdaki kanıtlar eklenmelidir:

- Playwright veya Jest sonuç özetleri  
- API response örnekleri (maskeli)  
- Log çıktıları (traceId maskeli)  
- Gerekiyorsa ekran görüntüleri  

---

# 6. Sonuç

Genel Durum: draft | review | ✔ done  
Tüm maddeler karşılandığında Story PROJECT_FLOW’da “✔ Tamamlandı” durumuna alınır ve session-log’a kapanış kaydı eklenir.

---

🟩 Uyum Özeti
- AGENT-CODEX ile uyumlu
- DOC-POLICY (1 sayfa – 5 başlık) ile uyumlu
- MP-STORY / MP-SPEC / MP-ADR ile uyumlu
- PROJECT_FLOW zincirine entegre
- STYLE-FE-001 / STYLE-BE-001 / STYLE-API-001 rehberleriyle uyumlu

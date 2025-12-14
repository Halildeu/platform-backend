# SPEC-E01-S05-IDENTITY-AUTH-V1
**Başlık:** Identity Auth & Shell Login v1.0  
**Versiyon:** v1.0  
**Tarih:** 2025-11-19  

**İlgili Dokümanlar:**  
- EPIC: `docs/05-governance/01-epics/E01_Identity.md`  
- ADR:  
  - `docs/05-governance/05-adr/ADR-003-auth-and-broadcast-channel.md`  
  - `docs/05-governance/05-adr/ADR-008-i18n-and-dictionary-packaging.md`  
  - `docs/05-governance/05-adr/ADR-016-theme-layout-system.md`  
- ACCEPTANCE: `docs/05-governance/07-acceptance/E01-S05-Login.acceptance.md`  
- STORY: `docs/05-governance/02-stories/E01-S05-Login.md`  
- STYLE GUIDE: `docs/00-handbook/NAMING.md`  

---

# 1. Amaç (Purpose)

Kurumsal ERP Shell için login/register/unauthorized akışlarının teknik tasarımını tanımlamak; auth servisleri ile entegrasyonu, i18n/tema entegrasyonunu ve BroadcastChannel bazlı login state senkronizasyonunu (ADR-003) netleştirmek.

---

# 2. Kapsam (Scope)

### Kapsam içi
- Shell login ve register ekranları (UI + iş akışı).
- Auth-service API entegrasyonu (login, register, session check).
- Dil/tema seçimlerinin auth UI ile entegrasyonu.

### Kapsam dışı
- Permission kontrolü ve registry (E08 kapsamı).
- MFA, sosyal login gibi ileri auth özellikleri (ileriki fazlar).

---

# 3. Tanımlar (Definitions)

- **Auth-service:** Login/register/refresh endpoint’lerini sağlayan backend servis.  
- **BroadcastChannel:** Aynı tarayıcıda açılan MFE sekmeleri arasında login state senkronizasyonu için kullanılan mekanizma.  

---

# 4. Kullanıcı Senaryoları (User Flows)

1. Kullanıcı login formunu doldurup giriş yapar; başarılı olduğunda Shell ve MFE’ler login state’i paylaşır.  
2. Kullanıcı logout yaptığında veya session süresi dolduğunda tüm sekmelerde unauthorized ekran gösterilir.  

---

# 5. Fonksiyonel Gereksinimler (Functional Requirements)

**FR-ID-01:** Login formu auth-service API’si ile entegre olmalı; başarılı yanıt sonrası access token/session bilgisi güvenli şekilde saklanmalıdır.  
**FR-ID-02:** Login state değiştiğinde BroadcastChannel üzerinden tüm MFE’lere bilgi iletilmelidir (ADR-003).  

---

# 6. İş Kuralları (Business Rules)

**BR-ID-01:** Başarısız login denemelerinde bilgi sızdırmayan hata mesajı gösterilmelidir (“Kullanıcı adı veya parola hatalı” gibi).  

---

# 7. Veri Modeli (Data Model)

Temel login request/response DTO’ları; detaylar ilgili API dokümanında (`docs/03-delivery/api/auth.api.md`) tanımlanır.

---

# 8. API Tanımı (API Spec)

Bu SPEC, `auth.api.md`’de tanımlanan endpoint’leri kullanır; yeni public endpoint eklemez.

---

# 9. Validasyon Kuralları (Validation Rules)

- Parola alanı zorunlu ve belirlenen kompleksite kurallarına uygun olmalıdır.  

---

# 10. Hata Kodları (Error Codes)

Auth-service tarafında tanımlanan hata kodları kullanılır; bu SPEC yeni kod seti eklemez.

---

# 11. Non-Fonksiyonel Gereksinimler (NFR)

- Login ekranı hafif ve hızlı yüklenmeli; tema/i18n yüklemesi TTFA bütçesini aşmamalıdır.  

---

# 12. İzlenebilirlik (Traceability)

Bu spekten türeyen dokümanlar:
- Story: `docs/05-governance/02-stories/E01-S05-Login.md`  
- Acceptance: `docs/05-governance/07-acceptance/E01-S05-Login.acceptance.md`  
- ADR: ADR-003, ADR-008, ADR-016  

Bu doküman, Identity auth & Shell login v1.0 için teknik tasarımın tek kaynağıdır.


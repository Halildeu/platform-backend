# SPEC-E07-S01-GLOBALIZATION-A11Y-V1
**Başlık:** Globalization & Accessibility v1.0  
**Versiyon:** v1.0  
**Tarih:** 2025-11-19  

**İlgili Dokümanlar:**  
- EPIC: `docs/05-governance/01-epics/E07_GlobalizationAndAccessibility.md`  
- ADR:  
  - `docs/05-governance/05-adr/ADR-008-i18n-and-dictionary-packaging.md`  
  - `docs/05-governance/05-adr/ADR-014-accessibility-process-standard.md`  
- ACCEPTANCE: `docs/05-governance/07-acceptance/E07-S01-Globalization-and-Accessibility.acceptance.md`  
- STORY: `docs/05-governance/02-stories/E07-S01-Globalization-and-Accessibility.md`  
- STYLE GUIDE: `docs/00-handbook/NAMING.md`  

---

# 1. Amaç (Purpose)

i18n sözlük paketleme stratejisini (ADR-008) ve A11y süreç standardını (ADR-014) uygulayarak:
- TR/EN/DE/ES ve ileride eklenecek diller için tutarlı i18n deneyimi sağlamak,
- WCAG-AA hedeflerine uygun erişilebilirlik süreçleri kurmak,
- Sözlük eksikliklerini ve A11y bulgularını ölçülebilir hale getirmek.

---

# 2. Kapsam (Scope)

### Kapsam içi
- TMS tabanlı sözlük pipeline’ı ve `@mfe/i18n-dicts` paket yapısı.
- Pseudolocale (örn. `qps-ploc`) ve fallback testlerinin tasarımı.
- A11y checklist’i ve CI pipeline’ına axe-core entegrasyonu.
- i18n ve A11y telemetry alanları (eksik çeviri, kontrast ihlali vb.).

### Kapsam dışı
- Tüm ekranların bir kerede i18n/A11y uyumlu hale getirilmesi (kademeli olarak, Story bazında yapılır).  
- Tasarım sisteminin tüm detayları (E03 Theme & Layout altında tanımlanır).

---

# 3. Tanımlar (Definitions)

- **TMS (Translation Management System):** Çeviri anahtarlarının ve hedef dil metinlerinin yönetildiği sistem (Tolgee/Weblate vb.).  
- **Pseudolocale:** String uzunluklarını ve layout esnekliğini test etmek için kullanılan yapay locale.  
- **A11y Checklist:** Screen reader turu, keyboard trap, kontrast ölçümü gibi kontrolleri içeren akış bazlı kontrol listesi.  

---

# 4. Kullanıcı Senaryoları (User Flows)

1. **Sözlük Güncelleme ve Dağıtım**
   - Ürün/UX, TMS üzerinde yeni i18n key ve çevirileri girer.
   - Pipeline, `@mfe/i18n-dicts` paketlerini semver ile üretir ve registry/CDN’e publish eder.
   - MFE’ler runtime’da veya build-time’da bu sözlükleri kullanır.

2. **Pseudolocale ve Fallback Testi**
   - CI veya nightly job, uygulamayı pseudolocale ile ayağa kaldırır.
   - Eksik çeviriler, görünür “!!!key!!!” veya benzeri işaretlerle tespit edilir.
   - Eksik key metrikleri telemetry’ye yazılır.

3. **A11y Süreç Uygulaması**
   - Her akış için A11y checklist çalıştırılır:
     - Screen reader turu (NVDA/VoiceOver),
     - Keyboard trap testi,
     - Kontrast ölçümleri.
   - CI pipeline’ında axe-core smoke senaryoları çalışır; kritik bulgu varsa pipeline kırmızı kalır.

---

# 5. Fonksiyonel Gereksinimler (Functional Requirements)

**FR-GLOB-01:** Tüm manifest tanımlı sayfalar için metinler i18n key’leri üzerinden çözümlenmelidir; hard‑coded metinler tespit edilip backlog’a alınmalıdır.  
**FR-GLOB-02:** i18n sözlük paketleri semantik versiyonlama ile yönetilmeli ve MFE’ler uyumlu versiyon aralığını belirtmelidir.  
**FR-GLOB-03:** A11y checklist her akışta en az bir kez uygulanmalı ve sonuçları kayıt altına alınmalıdır.  
**FR-GLOB-04:** axe-core veya benzeri araçlarla temel A11y kontrolleri CI pipeline’ında çalıştırılmalıdır.  

---

# 6. İş Kuralları (Business Rules)

**BR-GLOB-01:** Eksik çeviri/fallback oranı telemetry’de < %1 (p95) olacak şekilde izlenmeli; eşik aşıldığında alert üretilmelidir.  
**BR-GLOB-02:** Kritik A11y hataları için SLA belirlenmeli (örn. 1 akış döngüsü içinde kapanma); SLA ihlalleri raporlanmalıdır.  
**BR-GLOB-03:** A11y checklist tamamlanmadan ilgili Story `Done` durumuna alınamaz.  

---

# 7. Veri Modeli (Data Model)

## 7.1. i18n Sözlük Kaydı (Örnek JSON)

```json
{
  "key": "users.list.title",
  "values": {
    "tr": "Kullanıcılar",
    "en": "Users"
  },
  "meta": {
    "description": "Users grid page title",
    "module": "IDENTITY"
  }
}
```

## 7.2. A11y Bulgu Kaydı (Örnek)

| Alan      | Tip     | Açıklama                          |
|-----------|---------|-----------------------------------|
| id        | string  | Bulgu kimliği                     |
| severity  | string  | minor / major / critical          |
| type      | string  | contrast / keyboard / sr / other  |
| location  | string  | Sayfa ve bileşen bilgisi          |

---

# 8. API Tanımı (API Spec)

Bu SPEC, i18n sözlük ve A11y süreçlerini tarif eder; yeni public backend API’leri tanımlamaz.  
Gerektiğinde TMS veya admin panel entegrasyonları ayrı dokümanlarda ayrıntılandırılacaktır.

---

# 9. Validasyon Kuralları (Validation Rules)

- i18n key’leri naming standardına uygun olmalıdır (`domain.page.element.state`).  
- Sözlük paketleri semver ile versionlanmalı; breaking değişiklikler major versiyon gerektirir.  
- A11y bulguları için severity alanı zorunlu ve sınırlı kümeden seçilmelidir.  

---

# 10. Hata Kodları (Error Codes)

Bu SPEC external hata kodu tanımlamaz; A11y ve i18n için iç metrik ve alert kodları kullanılır:

| Kod             | Açıklama                               |
|-----------------|----------------------------------------|
| I18N_MISSING    | Eksik çeviri/fallback tespit edildi    |
| A11Y_CONTRAST   | Kontrast ihlali bulundu               |
| A11Y_KEYBOARD   | Keyboard trap veya fokus problemi      |

---

# 11. Non-Fonksiyonel Gereksinimler (NFR)

- Sözlük yükleme performansı kullanıcı deneyimini bozmayacak şekilde optimize edilmelidir (cache, ETag vb.).  
- A11y kontrollerinin test süresine ek yükü makul seviyede kalmalıdır; pipeline’daki süre artışı kabul edilebilir sınırlar içinde tutulur.  

---

# 12. İzlenebilirlik (Traceability)

Bu spekten türeyen dokümanlar:
- Story: `docs/05-governance/02-stories/E07-S01-Globalization-and-Accessibility.md`  
- Acceptance: `docs/05-governance/07-acceptance/E07-S01-Globalization-and-Accessibility.acceptance.md`  
- ADR:  
  - `docs/05-governance/05-adr/ADR-008-i18n-and-dictionary-packaging.md`  
  - `docs/05-governance/05-adr/ADR-014-accessibility-process-standard.md`  

Bu doküman, globalization & accessibility v1.0 için teknik tasarımın tek kaynağıdır.

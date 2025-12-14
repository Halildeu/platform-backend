# ADR-002 – PageLayout & Manifest Modeli

**Durum (Status):** Accepted  
**Tarih:** 2025-11-02  

**İlgili Dokümanlar (Traceability):**
- SPEC: docs/05-governance/06-specs/SPEC-E04-S01-PLATFORM-MANIFEST-V1.md
- ACCEPTANCE: docs/05-governance/07-acceptance/E04-S01-Manifest-Platform-v1.acceptance.md
- STORY: docs/05-governance/02-stories/E04-S01-Manifest-Platform-v1.md
- İlgili ADR’ler: ADR-001 (Remote Manifest & SRI), ADR-007 (Contract & Schema Gating)
- STYLE GUIDE: (Varsa STYLE-FE-001)

---

# 1. Bağlam (Context)

- Tüm MFE sayfalarının farklı yaklaşımlarla inşa edilmesi UX parçalanmasına ve bakım maliyetine neden oluyor.
- Manifest tabanlı tanım (başlık, breadcrumb, aksiyonlar, filtreler, grid kolonları, drawer sekmeleri) ile PageLayout iskeletine bağlamak tutarlılığı artıracak.
- Tasarım dili Ant Design + AG Grid üzerine kurulu; manifest modeli bu bileşenleri parametrik olarak sürmek zorunda.

---

# 2. Karar (Decision)

- Her sayfa bir `PageManifest` ile tanımlanacak ve Zod şeması ile doğrulanacak.
- Şemada bulunan meta, permission, filter, grid, detail ve action bilgileri `PageLayout` bileşeni tarafından render edilecek.
- Manifestler shell tarafından expose edilen servis sözleşmesini (`ShellServices`) kullanacak; kod paylaşılmayacak.
- Özel ihtiyaçlar için PageLayout varyantları (WizardLayout, SettingsLayout) sağlanacak ancak manifest formatını yeniden kullanacak.

---

# 3. Alternatifler (Alternatives)

Bu ADR’de değerlendirilen alternatifler ayrı başlık altında yazılmamıştır.  
İleride ihtiyaç duyulursa, örneğin farklı layout modelleri (pure React layout, route-bazlı layout vs.) burada karşılaştırmalı olarak dökümante edilebilir.

---

# 4. Gerekçeler (Rationale)

- Tek bir `PageLayout + PageManifest` modeline geçmek, MFE’ler arasında UX tutarlılığı sağlar.
- Manifest odaklı yaklaşım, tasarım değişikliklerinin merkezi olarak uygulanmasına izin verir.
- Zod şeması ile tip güvenliği ve CI’da doğrulama mümkündür.

---

# 5. Sonuçlar (Consequences)

- Manifest dışında kalan edge-case ekranlar için yeni varyant bileşenleri yazmamız gerekecek.
- Yeni sayfa geliştiren ekipler manifest şemasına uymak zorunda; schema breaking değişiklikler semver major olacak.
- Tasarım değişiklikleri PageLayout ve manifest şemasında güncellenerek tüm sayfalara yayılacak; test kapsamı ona göre artacak.

### Acceptance / Metrics

- En az iki farklı ekran yalnız manifest yazarak ayağa kaldırılmalı (kod tekrarına gerek yok).
- Soğuk başlangıçta TTFA 8 saniyenin altında kalmalı (manifest yükleme + PageLayout render).
- Schema uyumsuzluğu CI contract testlerinde build'i fail etmeli.

---

# 6. Uygulama Detayları (Implementation Notes)

- `PageManifest` şeması repo içinde versiyonlanmalı ve CI’da doğrulanmalıdır.
- PageLayout varyantlarının (`WizardLayout`, `SettingsLayout`) manifest alanlarıyla bire bir hizalı olması gerekir.

---

# 7. Durum Geçmişi (Status History)

| Tarih       | Durum    | Not                                   |
|-------------|----------|----------------------------------------|
| 2025-11-02  | Accepted | İlk sürüm, ADR-002 kararı kabul edildi |

---

# 8. Notlar

- Gelecekte manifest modelinin genişletilmesi (örn. dashboard, wizard senaryoları) yeni ADR’lerle kayıt altına alınmalıdır.

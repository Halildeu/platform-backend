# ADR-014 – Erişilebilirlik Süreç Standardı

**Durum (Status):** Accepted  
**Tarih:** 2025-11-02  

**İlgili Dokümanlar (Traceability):**
- SPEC: `docs/05-governance/06-specs/SPEC-E07-S01-GLOBALIZATION-A11Y-V1.md`
- ACCEPTANCE: `docs/05-governance/07-acceptance/E07-S01-Globalization-and-Accessibility.acceptance.md`
- STORY: E07-S01-Globalization-and-Accessibility.md
- İlgili ADR’ler: ADR-005
- STYLE GUIDE: (Varsa STYLE-FE-001 / STYLE-A11Y-001)

---

# 1. Bağlam (Context)

- ADR-005 performans ve erişilebilirlik hedefleri koydu, ancak süreç/denetim adımları tanımlanmadı.
- Manuel ekran okuyucu (NVDA/VoiceOver) testleri, keyboard trap denetimleri ve kontrast kontrolleri düzenli yapılmazsa WCAG-AA uyumu sağlanamaz.

---

# 2. Karar (Decision)

- Her akışta A11Y kontrol listesi uygulanacak: SR turu (NVDA/VoiceOver), keyboard trap testi, kontrast ölçümü (WCAG-AA).
- A11Y bulguları için ayrı bug sınıfı (severity) oluşturulacak ve SLA belirlenecek (örn. kritik a11y bug 1 akış döngüsü içinde kapanır).
- CI pipeline’ına axe-core veya benzeri otomatik kontrol eklenecek; manual checklist ile tamamlanacak.
- Tasarım ekibi A11Y gereksinimlerini Figma ve design kit’te işaretleyecek; geliştirici/test ekibi bunları doğrulayacak.

---

# 3. Alternatifler (Alternatives)

- Yalnız manuel testlere güvenmek
- A11Y kontrollerini yalnız release öncesi yapmak

Bu alternatifler sürdürülebilir erişilebilirlik sağlamadığı için tercih edilmemiştir.

---

# 4. Gerekçeler (Rationale)

- Süreç temelli A11Y kontrolleri, tek seferlik değil sürekli iyileşmeyi destekler.
- CI entegrasyonu, regresyonları erken tespit etmeye yardımcı olur.

---

# 5. Sonuçlar (Consequences)

- Test sürecine ek zaman eklenecek; A11Y sorumluları (QA/UX) belirlenmeli.
- A11Y hataları release bekletebilir; SLA takibi gerekli.
- Eğitim ihtiyacı (geliştirici ve QA için) olabilir.

### Acceptance / Metrics

- A11Y checklist her akışta tamamlanmış; backlog’da açık kritik a11y bug yok.
- Keyboard/accessibility testleri pipeline’da yeşil; manuel SR turu raporlanmış.
- WCAG-AA kontrast hedefleri sağlanıyor; dokümente ediliyor.

---

# 6. Uygulama Detayları (Implementation Notes)

- A11Y checklist ve SLA hedefleri dokümante edilmelidir.
- axe-core ve manuel testler için referans senaryo setleri hazırlanmalıdır.

---

# 7. Durum Geçmişi (Status History)

| Tarih       | Durum    | Not                                   |
|-------------|----------|----------------------------------------|
| 2025-11-02  | Accepted | İlk sürüm, ADR-014 kararı kabul edildi |

---

# 8. Notlar

- İleride WCAG sürüm güncellemeleri olduğunda bu ADR revize edilmelidir.

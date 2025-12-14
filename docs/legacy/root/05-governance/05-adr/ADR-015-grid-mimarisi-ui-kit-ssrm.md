# ADR-015 – Grid Mimarisi (UI Kit + SSRM)

**Durum (Status):** Accepted  
**Tarih:** 2025-11-03  

**İlgili Dokümanlar (Traceability):**
- SPEC: `docs/05-governance/06-specs/SPEC-E02-S02-GRID-REPORTING-V1.md`
- ACCEPTANCE: `docs/05-governance/07-acceptance/E02-S02-Grid-UI-Kit-SSRM.acceptance.md`
- STORY: E02-S02-Grid-UI-Kit-SSRM.md
- İlgili ADR’ler: ADR-005
- STYLE GUIDE: (Varsa STYLE-FE-001)

---

# 1. Bağlam (Context)

- Grid altyapısının farklı ekranlarda farklı çözümlerle uygulanması UX ve bakım maliyeti yaratmaktadır.
- ADR-005 ile AG Grid standardı belirlenmiş olsa da UI Kit entegrasyonu ve SSRM mimarisi netleştirilmemiştir.

---

# 2. Karar (Decision)

- Grid altyapısı ortak UI Kit bileşeni (`EntityGridTemplate`) üzerinden sunulur.
- Büyük veri ekranlarında SSRM (Server‑Side Row Model) default’tur; küçük veri için ClientSide desteklenir.
- Quick filter → `search`, advanced filter → `advancedFilter` (URL‑encoded JSON), sort → `sort` paramı ile API’ye aktarılır.
- SSRM istekleri coalesce edilir (aynı aralık/sort/filter tek backend isteği).

---

# 3. Alternatifler (Alternatives)

- Her MFE’nin kendi grid bileşenini yazması
- SSRM yerine yalnız client-side pagination/filter kullanmak

Bu alternatifler performans ve tutarlılık açısından yetersiz olduğu için tercih edilmemiştir.

---

# 4. Gerekçeler (Rationale)

- Performans, tutarlılık ve tekrar kullanılabilirlik; MFE’ler arası tek grid standardı.
- UI Kit bileşeni üzerinden tek noktadan davranış yönetimi, tema ve i18n entegrasyonunu kolaylaştırır.

---

# 5. Sonuçlar (Consequences)

- Backend’de güvenli parse/whitelist (advancedFilter) ve çoklu ORDER BY (sort) gereklidir.
- UI Kit sürümleme: Grid davranışı (tema/i18n/filtre) tek yerden güncellenir.

### Acceptance / Metrics

- Grid performans ve erişilebilirlik hedefleri ADR-005 ile uyumlu şekilde sağlanmalıdır.

---

# 6. Uygulama Detayları (Implementation Notes)

- `EntityGridTemplate` bileşeninin API yüzeyi stabil tutulmalı ve versiyonlanmalıdır.
- SSRM isteklerinin coalesce edilmiş olması logging ve monitoring ile doğrulanmalıdır.

---

# 7. Durum Geçmişi (Status History)

| Tarih       | Durum    | Not                                   |
|-------------|----------|----------------------------------------|
| 2025-11-03  | Accepted | İlk sürüm, ADR-015 kararı kabul edildi |

---

# 8. Notlar

Bağlantılar:
- `docs/01-architecture/01-system/02-frontend-architecture.md`
- `docs/01-architecture/01-system/01-backend-architecture.md`
- `frontend/docs/ag-grid-ssrm-export-strategy.md`

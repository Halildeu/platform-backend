# SPEC-E02-S02-GRID-REPORTING-V1
**Başlık:** Grid & Reporting v1.0 (AG Grid + EntityGridTemplate)  
**Versiyon:** v1.0  
**Tarih:** 2025-11-19  

**İlgili Dokümanlar:**  
- EPIC: `docs/05-governance/01-epics/E02_GridAndReporting.md`  
- ADR:  
  - `docs/05-governance/05-adr/ADR-005-ag-grid-standard-and-experience-budgets.md`  
  - `docs/05-governance/05-adr/ADR-015-grid-mimarisi-ui-kit-ssrm.md`  
- ACCEPTANCE: `docs/05-governance/07-acceptance/E02-S02-Grid-UI-Kit-SSRM.acceptance.md`  
- STORY: `docs/05-governance/02-stories/E02-S02-Grid-UI-Kit-SSRM.md`  
- STYLE GUIDE: `docs/00-handbook/NAMING.md`  

---

# 1. Amaç (Purpose)

AG Grid standardı (ADR-005) ve UI Kit `EntityGridTemplate` mimarisini (ADR-015) hayata geçirerek, tüm MFE’lerde tutarlı ve performanslı grid & raporlama deneyimi sağlamak.

---

# 2. Kapsam (Scope)

### Kapsam içi
- `EntityGridTemplate` bileşeni ve AG Grid konfigürasyonu.
- SSRM param sözleşmesi: `search`, `advancedFilter`, `sort`, `page`, `pageSize`.

### Kapsam dışı
- Her domain için tüm raporların detayları (ayrı Story’lerde ele alınır).  

---

# 3. Tanımlar (Definitions)

- **SSRM:** Server-Side Row Model; büyük veri setleri için AG Grid veri yükleme modeli.  
- **EntityGridTemplate:** UI Kit içinde grid ekranlarının ortak davranışını kapsülleyen bileşen.  

---

# 4. Kullanıcı Senaryoları (User Flows)

1. Kullanıcı filtreleme, sıralama ve sayfalama yaparken grid SSRM üzerinden backend ile konuşur; performans ve deneyim bütçeleri korunur.  

---

# 5. Fonksiyonel Gereksinimler (Functional Requirements)

**FR-GRID-01:** Tüm grid ekranları `EntityGridTemplate` üzerinden çalışmalı; doğrudan AG Grid konfigürasyonu yapılmamalıdır.  
**FR-GRID-02:** SSRM param sözleşmesi backend API’leriyle uyumlu olmalı; `search`, `advancedFilter`, `sort`, `page`, `pageSize` alanları standardize edilmelidir.  

---

# 6. İş Kuralları (Business Rules)

**BR-GRID-01:** Deneyim bütçeleri ADR-005’te tanımlanan hedefleri karşılamalıdır (render süreleri, FPS vb.).  

---

# 7. Veri Modeli (Data Model)

Grid request/response DTO’ları; detaylar ilgili API dokümanlarında (`users.api.md`, `reporting-schema-contract.md`) tanımlanır.

---

# 8. API Tanımı (API Spec)

Bu SPEC, ilgili domain API’leri üzerinde SSRM param sözleşmesini uygular; yeni public endpoint eklemez.

---

# 9. Validasyon Kuralları (Validation Rules)

- `page` ve `pageSize` pozitif sayılar olmalıdır.  
- `sort` alanı izin verilen sütun isimleriyle sınırlı olmalıdır.  

---

# 10. Hata Kodları (Error Codes)

Domain API’leri tarafından belirlenir; bu SPEC yeni kod seti eklemez.

---

# 11. Non-Fonksiyonel Gereksinimler (NFR)

- İlk render sıcak durumda p95 < 2.5s; grid etkileşimleri akıcı olmalıdır.  

---

# 12. İzlenebilirlik (Traceability)

Bu spekten türeyen dokümanlar:
- Story: `docs/05-governance/02-stories/E02-S02-Grid-UI-Kit-SSRM.md`  
- Acceptance: `docs/05-governance/07-acceptance/E02-S02-Grid-UI-Kit-SSRM.acceptance.md`  
- ADR: ADR-005, ADR-015  

Bu doküman, Grid & Reporting v1.0 için teknik tasarımın tek kaynağıdır.


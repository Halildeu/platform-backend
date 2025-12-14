# ADR-005 – AG Grid Standardı & Deneyim Bütçeleri

**Durum (Status):** Accepted  
**Tarih:** 2025-11-02  

**İlgili Dokümanlar (Traceability):**
- SPEC: `docs/05-governance/06-specs/SPEC-E02-S02-GRID-REPORTING-V1.md`
- ACCEPTANCE: `docs/05-governance/07-acceptance/E02-S02-Grid-UI-Kit-SSRM.acceptance.md`
- STORY: E02-S01-AG-Grid-Standard.md, E02-S02-Grid-UI-Kit-SSRM.md
- İlgili ADR’ler: ADR-004 (Telemetry & Audit), ADR-015 (Grid Mimarisi – UI Kit + SSRM)
- STYLE GUIDE: (Varsa STYLE-FE-001)

---

# 1. Bağlam (Context)

- Yönetim ekranlarının tamamında tablo/listeler yoğun kullanılıyor; farklı grid çözümleri UX parçalanması ve bakım zorluğu yaratıyor.
- Performans ve erişilebilirlik hedefleri (bundle boyutu, render süreleri, klavye erişimi) belirlenmemişti.

---

# 2. Karar (Decision)

- Tüm MFE’lerde tablo/listeler için AG Grid Enterprise kullanılacak; server-side row model ve lazy load varsayılan.
- `valueFormatter` öncelikli, custom `cellRenderer` yalnız gerekli olduğunda kullanılacak; varyant/vizyon kaydı ortak store üzerinden yönetilecek.
- Performans bütçeleri: Shell < 250 KB gzip, her MFE < 300 KB gzip (ilk route), ilk render sıcak durumda < 2.5 sn.
- Erişilebilirlik bütçesi: WCAG-AA, tam klavye gezinme, Drawer focus-trap + focus-return, aria etiketleri zorunlu.

---

# 3. Alternatifler (Alternatives)

Bu ADR’de alternatif grid çözümleri (örn. farklı grid kütüphaneleri) ayrıntılı yazılmamıştır;  
ancak tek standart grid kütüphanesi kullanmanın bakım maliyetini azalttığı varsayımıyla AG Grid tercih edilmiştir.

---

# 4. Gerekçeler (Rationale)

- Tek grid standardı, UX tutarlılığı ve bakım maliyetini azaltır.
- AG Grid Enterprise, server-side row model ve performans ihtiyaçlarını karşılar.
- Belirli performans ve erişilebilirlik bütçeleri, kalite çıtasını netleştirir.

---

# 5. Sonuçlar (Consequences)

- Backend API’larının AG Grid server-side parametrelerine (pagination, sort, filter model) uyum sağlaması şart.
- Tasarım prototipleri AG Grid kontrol setine göre hazırlanacak; alternatif grid istekleri ADR gerektirecek.
- Perf/a11y testleri CI pipeline’ına eklenecek (bundle-size gate, Lighthouse, keyboard tests).

### Acceptance / Metrics

- İlk render sıcak durumda 2.5 saniyenin altında (p95); soğuk TTFA hedefleri ADR-004 ile uyumlu.
- Perf ve a11y denetimleri pipeline’da yeşil (Lighthouse ≥ 80, axe-core hatasız).
- AG Grid lisans anahtarı runtime’da (Vault/ENV) enjekte ediliyor; bundle’da hard-code edilmiyor.

---

# 6. Uygulama Detayları (Implementation Notes)

- Grid komponentleri UI Kit altında tek bir bileşen seti ile sunulmalıdır.
- Backend endpoint’leri AG Grid SSRM ile uyumlu parametre sözleşmesini sağlamalıdır.

---

# 7. Durum Geçmişi (Status History)

| Tarih       | Durum    | Not                                   |
|-------------|----------|----------------------------------------|
| 2025-11-02  | Accepted | İlk sürüm, ADR-005 kararı kabul edildi |

---

# 8. Notlar

- Grid mimarisi detayları ADR-015’te genişletilmiştir.

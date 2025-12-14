# Story E02-S02 – Grid Mimarisi (UI Kit EntityGridTemplate + SSRM)

- Epic: E02 – Grid & Reporting  
- Story Priority: 220  
- Tarih: 2025-12-05  
- Durum: Done

## Kısa Tanım

UI Kit `EntityGridTemplate` bileşeni üzerinden grid altyapısını merkezileştirmek; SSRM param sözleşmesini (`search`, `advancedFilter`, `sort`, `page/pageSize`) FE/BE tarafında oturtmak ve Users/Reporting MFE’lerini bu mimariye taşımak.

## İş Değeri

- Grid davranışı (parametre sözleşmesi, i18n, tema) tek bileşende toplanır; MFE’ler arası tekrar ve drift azalır.
- Backend ile grid parametre sözleşmesi netleşerek SSRM istekleri güvenli ve performanslı hale gelir.
- Yeni grid ekranı eklemek, EntityGridTemplate konfigürasyonu ile sınırlı hale gelir.

## Bağlantılar (Traceability Links)

- SPEC: `docs/05-governance/06-specs/SPEC-E02-S02-GRID-REPORTING-V1.md`  
- ACCEPTANCE: `docs/05-governance/07-acceptance/E02-S02-Grid-UI-Kit-SSRM.acceptance.md`  
- ADR: ADR-015 (Grid Mimarisi – UI Kit + SSRM), ADR-005 (AG Grid Standardı & Deneyim Bütçeleri)  
- STYLE GUIDE: `docs/00-handbook/NAMING.md`

## Kapsam

### In Scope
- UI Kit `EntityGridTemplate` bileşeninin grid davranışının (parametre mapping, SSRM/Client seçimi) kanonik kaynağı haline getirilmesi.
- SSRM parametre sözleşmesi:
  - Quick filter → `search`,
  - Advanced filter → `advancedFilter` (URL-encoded JSON),
  - Sort → `sort` (`field,dir;field2,dir2`),
  - Pagination → `page`, `pageSize`.
- Backend sözleşmesi:
  - `advancedFilter` JSON parse + whitelist,
  - Çoklu ORDER BY (`sort`) desteği.
- Users ve Reporting MFE’lerinde mevcut gridlerin bu şemaya taşınması.

### Out of Scope
- AG Grid perf/a11y bütçe testlerinin detay konfigürasyonu (E02-S01 altında).
- Yeni domain-spesifik grid özellikleri (örn. inline edit, çok karmaşık custom renderer’lar).

## Task Flow (Ready → InProgress → Review → Done)

```text
+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
| Task                                                        | Ready        | InProgress    | Review       | Done        |
+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
| EntityGridTemplate SSRM parametre mapping’inin implementasyonu| 2025-12-05   | 2025-12-05    | 2025-12-05   | 2025-12-05   |
| Backend endpoint’lerinin advancedFilter/sort sözleşmesine uyarlanması| 2025-12-05 | 2025-12-05    | 2025-12-05   | 2025-12-05   |
| Users grid’lerinin EntityGridTemplate + SSRM’e taşınması    | 2025-12-05   | 2025-12-05    | 2025-12-05   | 2025-12-05   |
| Reporting grid’lerinin EntityGridTemplate + SSRM’e taşınması| 2025-12-05   | 2025-12-05    | 2025-12-05   | 2025-12-05   |
| SSRM filter/sort/pagination için integration testlerinin yazılması| 2025-12-05 | 2025-12-05    | 2025-12-05   | 2025-12-05   |
| Grid parametre sözleşmesi için dokümantasyonun güncellenmesi| 2025-12-05   | 2025-12-05    | 2025-12-05   | 2025-12-05   |
+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
```

**Kural:**
- İlk tarih “Ready” → görev hazır  
- İkinci tarih “InProgress” → çalışma başladı  
- Üçüncü tarih “Review” → kod kontrol aşamasında  
- Son tarih “Done” → tamamen tamamlandı  
- Soldaki sütun dolmadan sağdaki sütun doldurulmaz (Ready → InProgress → Review → Done akışına uyulur).

## Fonksiyonel Gereksinimler

1. UI Kit `EntityGridTemplate` grid parametrelerini yukarıdaki sözleşmeye uygun şekilde URL ve API çağrılarına map etmelidir.
2. Users/Reporting grid’leri EntityGridTemplate üzerinden konfigüre edilmeli; local ad-hoc grid konfigleri kaldırılmalıdır.
3. Backend endpoint’leri `advancedFilter` JSON’unu whitelist’e göre parse etmeli; unsupported field/operator için 400 döndürmelidir.
4. Çoklu sort ORDER BY davranışı FE `sortModel` ile uyumlu çalışmalıdır.

## Non-Functional Requirements

- SSRM istekleri aynı aralık/sort/filter için coalesce edilmeli (gereksiz tekrarlı çağrılar engellenmeli).
- Grid ekranları parametre değişimlerinde gereksiz rerender/reflow yapmamalıdır.

## İş Kuralları / Senaryolar

- “Yeni grid ekranı” → EntityGridTemplate konfigürasyonu ile eklenir, parametre sözleşmesine uyar.
- “API param hatası” → backend whitelist 400 döndürür; FE tarafı kullanıcıya uygun hata mesajı gösterir.

## Interfaces (API / DB / Event)

- `GET /api/users/all` ve benzeri grid endpointleri:
  - `search`, `advancedFilter`, `sort`, `page`, `pageSize` parametrelerini destekler.
- DB tarafında ilgili indeksler (örn. `lower(email)`, `create_date`, vb.) ADR-015 gereği hazırlanmıştır.

## Acceptance Criteria

Bu Story için güncel acceptance kriterleri:  
`docs/05-governance/07-acceptance/E02-S02-Grid-UI-Kit-SSRM.acceptance.md`

## Definition of Done

- [x] EntityGridTemplate SSRM sözleşmesi acceptance maddelerine göre uygulanmış olmalı.  
- [x] Acceptance dosyasındaki checklist (`docs/05-governance/07-acceptance/E02-S02-Grid-UI-Kit-SSRM.acceptance.md`) tam karşılanmış olmalı.  
- [x] ADR-015 ve ilgili grid mimarisi kararları uygulanmış olmalı.  
- [x] Kod, `docs/00-handbook/NAMING.md` ve stil kurallarına uyumlu olmalı.  
- [x] Kod review’dan onay almış olmalı.  
- [x] Unit/E2E testler (özellikle SSRM ve advancedFilter) yeşil olmalı.  
- [x] `docs/05-governance/PROJECT_FLOW.md` üzerinde Son Durum ✔ Done olarak işlenmiş olmalı.

## Notlar

- Backend whitelist ile FE filter builder arasında drift tespit edilirse bu Story altında yeni task açılmalıdır.

## Dependencies

- ADR-015 – Grid Mimarisi (UI Kit + SSRM).
- ADR-005 – AG Grid Standardı & Deneyim Bütçeleri.

## Risks

- Legacy grid konfiglerinin tamamen kaldırılmaması; iki farklı param sözleşmesinin paralel yaşaması.
- Backend whitelist’in FE tarafındaki filter builder ile drift etmesi.

## Flow / Iteration İlişkileri

| Flow ID   | Durum        | Not                                                                 |
|-----------|-------------|---------------------------------------------------------------------|
| Flow-02   | In Progress | Grid/advancedFilter/sort işleri bu Story kapsamında, PROJECT_FLOW akışında ticket seviyesinde izlenir. |

## İlgili Artefaktlar

- `docs/05-governance/05-adr/ADR-015-grid-mimarisi-ui-kit-ssrm.md`
- `docs/01-architecture/01-system/02-frontend-architecture.md` (grid/MFE mimari bağlantıları)
- `docs/01-architecture/01-system/01-backend-architecture.md` (advancedFilter/search/sort sözleşmesi)

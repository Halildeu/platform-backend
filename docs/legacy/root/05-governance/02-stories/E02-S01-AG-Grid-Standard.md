# Story E02-S01 – AG Grid Standardı & Deneyim Bütçeleri

- Epic: E02 – Grid & Reporting  
- Story Priority: 210  
- Tarih: 2025-12-04  
- Durum: Done

## Kısa Tanım

Tüm MFE’lerde tablo/listeler için AG Grid Enterprise kullanımını standardize etmek; performans (bundle boyutu, render süresi) ve erişilebilirlik (WCAG-AA, klavye erişimi) bütçelerini tanımlayıp CI pipeline’ında doğrulamak.

## İş Değeri

- Grid deneyimi tüm MFE’lerde tutarlı hale gelir; kullanıcı farklı modüllerde farklı grid davranışları görmez.
- Performans ve a11y hedefleri netleşir; regression’lar CI içinde otomatik yakalanır.
- AG Grid lisans ve konfigürasyonu tek yerden yönetilerek bakım maliyeti azalır.

## Bağlantılar (Traceability Links)

- SPEC: `docs/05-governance/06-specs/SPEC-E02-S01-AG-Grid-Standard.md`  
- ACCEPTANCE: `docs/05-governance/07-acceptance/E02-S01-AG-Grid-Standard.acceptance.md`  
- ADR: ADR-005 (AG Grid Standard & Experience Budgets), ADR-004 (Telemetry & Audit Taksonomisi)  
- STYLE GUIDE: `docs/00-handbook/NAMING.md`

## Kapsam

### In Scope
- AG Grid Enterprise’ın tüm grid ekranlarında standart olarak kullanılması (Users, Access, Reporting, Audit).
- Varsayılan modelin server-side row model (SSRM) ve lazy load olması.
- `valueFormatter` vs `cellRenderer` kullanım rehberi (yalnız gerektiğinde custom renderer).
- Perf/a11y bütçelerinin dokümantasyonu ve CI pipeline’ında bundle-size + Lighthouse + axe-core testlerinin çalıştırılması.

### Out of Scope
- UI Kit `EntityGridTemplate` ve SSRM param sözleşmesinin detay implementasyonu (E02-S02 altında ele alınır).
- Backend API tasarımındaki büyük değişiklikler (örn. yeni endpoint’ler, büyük schema refactor’ları).

## Task Flow (Ready → InProgress → Review → Done)

```text
+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
| Task                                                        | Ready        | InProgress    | Review       | Done        |
+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
| AG Grid standardı ve perf/a11y bütçelerinin dokümante edilmesi| 2025-11-18   | 2025-11-18    | 2025-12-05   | 2025-12-05   |
| AG Grid Enterprise temel konfigürasyonunun uygulanması      | 2025-12-04   | 2025-12-04    | 2025-12-05   | 2025-12-05   |
| Users MFE grid ekranlarının standarda taşınması             | 2025-11-18   | 2025-11-18    | 2025-12-05   | 2025-12-05   |
| Reporting MFE grid ekranlarının standarda taşınması         | 2025-12-04   | 2025-12-04    | 2025-12-05   | 2025-12-05   |
| Perf/a11y testlerinin CI pipeline’ına entegre edilmesi      | 2025-11-18   | 2025-11-18    | 2025-12-05   | 2025-12-05   |
| Legacy grid implementasyonlarının temizlenmesi              | 2025-12-04   | 2025-12-04    | 2025-12-05   | 2025-12-05   |
+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
```

**Kural:**
- İlk tarih “Ready” → görev hazır  
- İkinci tarih “InProgress” → çalışma başladı  
- Üçüncü tarih “Review” → kod kontrol aşamasında  
- Son tarih “Done” → tamamen tamamlandı  
- Soldaki sütun dolmadan sağdaki sütun doldurulmaz (Ready → InProgress → Review → Done akışına uyulur).

## Fonksiyonel Gereksinimler

1. Tüm grid ekranları AG Grid Enterprise kullanmalı; alternatif grid çözümleri (HTML table, başka kütüphane) kullanılmamalı.
2. SSRM büyük veri ekranlarında varsayılan; küçük veri ekranlarında ClientSide seçeneği dokümante edilmelidir.
3. Grid komponentlerinde formatlama öncelikle `valueFormatter` ile sağlanmalı; custom `cellRenderer` yalnızca şart olduğunda kullanılmalıdır.
4. Perf ve a11y testleri CI pipeline’ında çalışmalı; kırmızı olduğunda merge engellenmelidir.

## Non-Functional Requirements

- Performans:
  - Shell < 250 KB gzip, her MFE < 300 KB gzip (ilk route) hedefleri.
  - İlk render p95 < 2.5s (sıcak durumda).
- Erişilebilirlik:
  - Tüm grid ekranları için WCAG-AA; klavye ile dolaşılabilir hücreler, doğru ARIA etiketleri.

## İş Kuralları / Senaryolar

- “Yeni grid ekranı” → AG Grid Enterprise + SSRM/ClientSide kararı bu Story’deki rehbere göre verilir.
- “Performans/a11y budget ihlali” → CI kırmızı olur; ihlal giderilmeden PR merge edilmez.

## Interfaces (API / DB / Event)

- Grid backend endpoint’leri SSRM parametreleriyle uyumlu çalışmalıdır (pagination, filter, sort); ayrıntı E02-S02 altında ele alınır.

## Acceptance Criteria

Bu Story için güncel acceptance kriterleri:  
`docs/05-governance/07-acceptance/E02-S01-AG-Grid-Standard.acceptance.md`

## Definition of Done

- [x] Tüm hedef grid ekranları için AG Grid Standardı acceptance maddeleri sağlanmış olmalı.  
- [x] Acceptance dosyasındaki checklist (`docs/05-governance/07-acceptance/E02-S01-AG-Grid-Standard.acceptance.md`) tam karşılanmış olmalı.  
- [x] ADR-005 ve ilişkili kararlardaki grid standartları uygulanmış olmalı.  
- [x] Kod, `docs/00-handbook/NAMING.md` ve frontend stil rehberlerine uyumlu olmalı.  
- [x] Kod review’dan onay almış olmalı.  
- [x] Unit/E2E testler (özellikle grid perf/a11y testleri) yeşil olmalı.  
- [x] `docs/05-governance/PROJECT_FLOW.md` üzerinde Son Durum ✔ Done olarak işlenmiş olmalı.

## Notlar

- Flaky perf/a11y gate’leri gözlenirse geçici disable yerine eşik ayarı/doğrulama stratejisi güncellenmelidir.

## Dependencies

- ADR-005 – AG Grid Standardı & Deneyim Bütçeleri.
- ADR-004 – Telemetry & Audit Taksonomisi (ölçüm hedefleri için).

## Risks

- Eski grid çözümlerinin tamamen kaldırılmaması; kullanıcıların tutarsız deneyim yaşaması.
- Perf/a11y gate’lerinin CI’da flaky hale gelmesi ve devre dışı bırakılması.

## Flow / Iteration İlişkileri

| Flow ID   | Durum        | Not                                                                 |
|-----------|-------------|---------------------------------------------------------------------|
| Flow-02   | In Progress | İlk uygulama dalgası; Users ve Reporting MFE grid ekranları bu akışta ele alınır. |

## İlgili Artefaktlar

- `docs/05-governance/05-adr/ADR-005-ag-grid-standard-and-experience-budgets.md`
- `frontend/docs/01-architecture/02-ui-kit/02-ag-grid-theme.md`

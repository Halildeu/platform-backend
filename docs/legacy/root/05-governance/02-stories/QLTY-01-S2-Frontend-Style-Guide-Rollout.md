# Story QLTY-01-S2 – Frontend'de STYLE-FE-001 Yaygınlaştırma

- Epic: QLTY – Kod Kalitesi El Kitabı Yaygınlaştırma  
- Story Priority: 055  
- Tarih: 2025-11-19  
- Durum: In Progress

## Kısa Tanım

Bu Story, frontend MFE projelerinde `STYLE-FE-001` ve FE proje/dizin yapısı (`docs/00-handbook/FRONTEND-PROJECT-LAYOUT.md`, kanonik detay: `frontend/docs/architecture/frontend/frontend-project-layout.md`) rehberlerinin günlük geliştirme ve PR sürecinde kullanılmasını yaygınlaştırır.

## İş Değeri

- Frontend kod ve klasör yapısını tek bir stil rehberi altında toplar.  
- Yeni ekran/feature eklerken component tipi, state yönetimi ve API entegrasyonu için net kurallar sağlar.  
- Code review’lerde frontend stil tartışmalarını minimize eder.

## Bağlantılar (Traceability Links)

- SPEC: (ileride gerekirse) `docs/05-governance/06-specs/SPEC-FRONTEND-STYLE-FE-001-ROLLUP.md`  
- ACCEPTANCE: `docs/05-governance/07-acceptance/QLTY-01-S2-Frontend-Style-Guide-Rollout.acceptance.md`  
- STYLE GUIDE:  
  - `docs/00-handbook/STYLE-FE-001.md`  
  - `docs/00-handbook/FRONTEND-PROJECT-LAYOUT.md` (kanonik detay: `frontend/docs/architecture/frontend/frontend-project-layout.md`)

## Kapsam

### In Scope
- STYLE-FE-001’in güncellenmesi/gözden geçirilmesi ve erişilebilir olması.  
- Giriş/durum dokümanlarında (handbook README) FE stil rehberine açık referans bulunması.  
- PR template’te frontend değişikliklerinde FE stil rehberinin dikkate alınması (style-guides checkbox).

### Out of Scope
- Design system veya Figma kitinin detaylı tanımı (E03-S01 kapsamı).  
- Backend ve API stil rehberlerine ilişkin değişiklikler.

## Task Flow (Ready → InProgress → Review → Done)

```text
## Task Flow (Ready → InProgress → Review → Done)

+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
| Task                                                        | Ready        | InProgress    | Review       | Done        |
+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
| STYLE-FE-001 içeriğini gözden geçir ve referansları güncelle| 2025-11-19   | 2025-11-19    | 2025-11-19   | 2025-11-19  |
| Giriş/durum dokümanlarında FE stil rehberine referans ver | 2025-11-19   | 2025-11-19    | 2025-11-19   | 2025-11-19  |
| FE proje yapısı dokümanını (FRONTEND-PROJECT-LAYOUT) linkle | 2025-11-19   | 2025-11-19    | 2025-11-19   | 2025-11-19  |
| 2–3 FE PR'ında FE stil rehberine göre review uygula         |              |               |              |             |
+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
```

## Fonksiyonel Gereksinimler

1. FE stil rehberi (`STYLE-FE-001`) PROJECT_FLOW/handbook README üzerinden bulunabilir olmalı.  
2. FE proje/dizin yapısı dokümanı referanslardan erişilebilir olmalı.  
3. FE PR’ları açılırken PR template’te style-guides kutusu FE stil rehberi dikkate alınarak işaretlenmeli.

## Non-Functional Requirements

- Performance: FE stil dokümanı okunabilir ve örneklerle desteklenmiş olmalı.  
- UX / A11y: Örnek component ve state yönetimi desenleri a11y/i18n dikkate alınarak yazılmalı.

## İş Kuralları / Senaryolar

- “Yeni FE ekranı ekleme” → Geliştirici component tipini, state yönetimini ve API entegrasyonunu FE stil rehberine göre seçer.  
- “FE PR review” → Reviewer, FE stil rehberi üzerinden kontrol noktalarını kullanır.

## Interfaces (API / DB / Event)

### API
- FE tarafında API kullanımı `STYLE-API-001` ile uyumlu olacak şekilde kontrol edilir, ancak yeni API tanımı yapılmaz.

### Database
- Değişiklik yok.

### Events
- Değişiklik yok.

## Acceptance Criteria

Bu Story için güncel acceptance kriterleri:  
`docs/05-governance/07-acceptance/QLTY-01-S2-Frontend-Style-Guide-Rollout.acceptance.md`

## Definition of Done

- [x] `STYLE-FE-001` erişilebilir ve güncel olmalı.  
- [x] Giriş/durum dokümanları (handbook README) FE stil rehberine referans verecek şekilde güncellenmiş olmalı.  
- [x] FE proje yapısı dokümanı referanslardan erişilebilir olmalı.  
- [ ] En az 2–3 FE PR’ında style-guides kutusu FE stil rehberi referansıyla işaretlenmiş olmalı.

## Notlar

- FE tarafında i18n/a11y süreçleri E07-S01 ile paralel ilerler; çakışmaları önlemek için Story sahipleri haberleşmelidir.

## Dependencies
- `docs/00-handbook/FRONTEND-PROJECT-LAYOUT.md` (kanonik detay: `frontend/docs/architecture/frontend/frontend-project-layout.md`)

## Risks
- FE stil rehberine uyum için tooling (ESLint kuralları vb.) eksik kalırsa, kuralların uygulanabilirliği azalabilir.

## Flow / Iteration İlişkileri
| Flow ID   | Durum        | Not                    |
|-----------|-------------|------------------------|
| Flow-01   | In Progress | Tanıtım ve ilk kullanım|

## İlgili Artefaktlar
- `docs/00-handbook/STYLE-FE-001.md`  
- `docs/00-handbook/FRONTEND-PROJECT-LAYOUT.md` (kanonik detay: `frontend/docs/architecture/frontend/frontend-project-layout.md`)

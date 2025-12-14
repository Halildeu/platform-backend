# Story QLTY-01-S4 – Quality Index + PR Checklist Eğitim / Duyuru

- Epic: QLTY – Kod Kalitesi El Kitabı Yaygınlaştırma  
- Story Priority: 065  
- Tarih: 2025-11-19  
- Durum: In Progress

## Kısa Tanım

Bu Story, stil rehberleri ve PR checklist’ini ekip için görünür ve alışkanlık haline getirir; böylece kalite dokümanları sadece yazılı değil, günlük akışta kullanılan araçlar olur.

## İş Değeri

- Yeni gelenler için “nereden başlamalıyım?” sorusuna net cevap verir.  
- PR incelemelerinde ortak checklist’in kullanılmasını sağlar.  
- Stil ve kalite dokümanlarının atıl kalmasını engeller.

## Bağlantılar (Traceability Links)

- SPEC: (ileride gerekirse) `docs/05-governance/06-specs/SPEC-QUALITY-HANDBOOK-ADOPTION.md`  
- ACCEPTANCE: `docs/05-governance/07-acceptance/QLTY-01-S4-Quality-Handbook-Adoption.acceptance.md`  
- STYLE GUIDE:  
  - `docs/00-handbook/STYLE-BE-001.md`  
  - `docs/00-handbook/STYLE-FE-001.md`  
  - `docs/00-handbook/STYLE-API-001.md`

## Kapsam

### In Scope
- Stil rehberlerinin handbook README üzerinden referans olarak bulunabilirliği.  
- PR template’e style-guides ve spec-acceptance checkbox’larının eklenmesi ve duyurulması.  
- En az 1–2 kısa “nasıl kullanılır” örneğinin (veya mini kılavuzun) yazılması.

### Out of Scope
- Stil rehberlerinin içeriğinin baştan yazılması (S1/S2/S3 kapsamı).  
- Takım içi kapsamlı eğitim workshop’ları (ileride ayrı Story olabilir).

## Task Flow (Ready → InProgress → Review → Done)

```text
## Task Flow (Ready → InProgress → Review → Done)

+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
| Task                                                        | Ready        | InProgress    | Review       | Done        |
+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
| Stil rehberleri için görünür yönlendirme oluştur            | 2025-11-19   | 2025-11-19    |              |             |
| Handbook README’e stil rehberi linklerini ekle              | 2025-11-19   | 2025-11-19    |              |             |
| PR template'e style-guides + spec-acceptance kutularını ekle| 2025-11-19   | 2025-11-19    |              |             |
| Kısa bir “nasıl kullanılır” örnek akışı yaz                |              |               |              |             |
| 3–5 PR'da checklist kullanımını gözlemle ve not al          |              |               |              |             |
+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
```

## Fonksiyonel Gereksinimler

1. Stil rehberleri handbook README’den erişilebilir olmalı.  
2. PR template, stil ve spec/acceptance uyumu için açık checkbox’lar içermeli.  
3. En az bir dokümanda kalite rehberlerinin nasıl kullanılacağına dair kısa örnek akışlar olmalı.

## Non-Functional Requirements

- Duyuru metinleri kısa ve anlaşılır olmalı; gereksiz jargon içermemeli.  
- Dokümanlar “Az ama Öz” felsefesine uygun olmalı.

## İş Kuralları / Senaryolar

- “Yeni kişi onboarding” → Handbook README → ilgili stil rehberleri sırasıyla okunur.  
- “PR açarken” → PR template’te style-guides ve spec-acceptance kutularına göre gerekli dokümanlar kontrol edilir.

## Interfaces (API / DB / Event)

Bu Story yalnız doküman ve süreçleri etkiler; API/DB/Event değişikliği yoktur.

## Acceptance Criteria

Bu Story için güncel acceptance kriterleri:  
`docs/05-governance/07-acceptance/QLTY-01-S4-Quality-Handbook-Adoption.acceptance.md`

## Definition of Done

- [ ] Stil rehberleri handbook README’de referans verilmiş olmalı.  
- [ ] PR template’te style-guides ve spec-acceptance kutuları aktif kullanılmalı.  
- [ ] En az birkaç PR’da bu kutuların gerçekten doldurulduğu gözlemlenmiş olmalı.  
- [ ] `PROJECT_FLOW.md` üzerinde QLTY-01-S4 için Son Durum güncel olmalı.

## Notlar

- Takım içi kısa bir ekran paylaşımı/eğitim yapılması önerilir; zamanlama ayrı bir notta tutulabilir.

## Dependencies
- `docs/00-handbook/AGENT-CODEX.md`

## Risks
- Rehberler duyurulsa bile alışkanlık haline gelmesi zaman alabilir.

## Flow / Iteration İlişkileri
| Flow ID   | Durum    | Not                    |
|-----------|---------|------------------------|
| Flow-02   | Planned | Duyuru + kullanım takibi|

## İlgili Artefaktlar
- `.github/PULL_REQUEST_TEMPLATE.md`

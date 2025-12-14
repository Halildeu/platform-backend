# Story QLTY-01-S3 – API Dokümanlarını STYLE-API-001 ile Hizalama

- Epic: QLTY – Kod Kalitesi El Kitabı Yaygınlaştırma  
- Story Priority: 060  
- Tarih: 2025-11-19  
- Durum: Done

## Kısa Tanım

Bu Story, mevcut API dokümanlarını (`docs/03-delivery/api/*.md`) `STYLE-API-001` rehberine göre gözden geçirip hizalar; path/param/response/hata yapısının tek sözleşmeye oturmasını sağlar.

## İş Değeri

- Backend servisleri ve frontend arasında tek API sözleşmesi kullanılır.  
- Yeni API’ler için tekrar tekrar format tartışması yapma ihtiyacını azaltır.  
- QA ve test ekipleri için API davranışı daha öngörülebilir hale gelir.

## Bağlantılar (Traceability Links)

- SPEC: (ileride gerekirse) `docs/05-governance/06-specs/SPEC-API-STYLE-001-ROLLUP.md`  
- ACCEPTANCE: `docs/05-governance/07-acceptance/QLTY-01-S3-API-Style-Guide-Rollout.acceptance.md`  
- STYLE GUIDE:  
  - `docs/00-handbook/STYLE-API-001.md`  
  - `docs/03-delivery/api/README.md`

## Kapsam

### In Scope
- `users.api.md`, `auth.api.md`, `audit-events.api.md`, `common-headers.md` dokümanlarının STYLE-API-001 ile hizalanması.  
- API dokümanlarının response envelope, error yapısı, pagination/sort/filter parametreleri açısından tutarlı hale getirilmesi.  

### Out of Scope
- Yeni API endpoint tasarlamak (yalnız mevcutları belgelemek/hizalamak).  
- Gateway veya discovery topolojisini değiştirmek.

## Task Flow (Ready → InProgress → Review → Done)

```text
## Task Flow (Ready → InProgress → Review → Done)

+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
| Task                                                        | Ready        | InProgress    | Review       | Done        |
+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
| STYLE-API-001'i finalize et ve referansları güncelle        |              |               |              |             |
| users.api.md dokümanını STYLE-API-001 ile hizala            |              |               |              |             |
| auth.api.md dokümanını STYLE-API-001 ile hizala             |              |               |              |             |
| audit-events.api.md ve common-headers.md'i hizala           |              |               |              |             |
| ACCEPTANCE_INDEX ve API README'yi güncelle                  |              |               |              |             |
+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
```

## Fonksiyonel Gereksinimler

1. Kritik API dokümanları STYLE-API-001’de tanımlanan path/param/response formatlarını kullanmalı.  
2. API dokümanları pagination/sort/filter/advancedFilter sözleşmesini net olarak anlatmalı.  
3. Error response ve HTTP kod tablosu API dokümanları içinde tutarlı olmalı.

## Non-Functional Requirements

- Dokümanlar tek sayfa ve taranabilir olmalı; örnekler minimal ama yeterli olmalı.  
- API değişiklikleri backward-compatible olmalı; doküman, mevcut durumu yansıtmalı.

## İş Kuralları / Senaryolar

- “Yeni API dokümanı ekleme” → Geliştirici `STYLE-API-001` ve mevcut `*.api.md` dosyalarını örnek alarak ilerler.  
- “API değişikliği yapma” → İlgili `*.api.md` dokümanı aynı Story içinde güncellenir.

## Interfaces (API / DB / Event)

### API
- Bu Story, var olan API’lerin dokümantasyonunu hizalar; runtime davranışın değişmesi gerekiyorsa ayrı Story/ADR ile yönetilir.

### Database
- Değişiklik yok.

### Events
- Değişiklik yok.

## Acceptance Criteria

Bu Story için güncel acceptance kriterleri:  
`docs/05-governance/07-acceptance/QLTY-01-S3-API-Style-Guide-Rollout.acceptance.md`

## Definition of Done

- [ ] STYLE-API-001 güncel ve erişilebilir olmalı.  
- [ ] users/api/auth/audit-events/common-headers dokümanları STYLE-API-001 ile tutarlı hale gelmiş olmalı.  
- [ ] API README, STYLE-API-001’e referans vermeli.  
- [ ] PR template’te style-guides kutusu API değişiklikleri için de kullanılmış olmalı.

## Notlar

- Eğer runtime API davranışı ile doküman arasında fark çıkarsa, öncelik runtime’ı belgeye uydurmak yerine farkı bir Story olarak kaydedip karara bağlamaktır.

## Dependencies
- `docs/00-handbook/STYLE-API-001.md`  
- `docs/03-delivery/api/users.api.md`  
- `docs/03-delivery/api/auth.api.md`

## Risks
- Dokümanlar runtime gerçekliğini yansıtmazsa, FE/QA tarafında yanlış beklenti yaratabilir; bu nedenle doküman hizalaması için küçük smoke testler önerilir.

## Flow / Iteration İlişkileri
| Flow ID   | Durum    | Not                    |
|-----------|---------|------------------------|
| Flow-02   | Planned | API doküman hizalama   |

## İlgili Artefaktlar
- `docs/00-handbook/STYLE-API-001.md`  
- `docs/03-delivery/api/*.md`  
- `docs/03-delivery/api/README.md`

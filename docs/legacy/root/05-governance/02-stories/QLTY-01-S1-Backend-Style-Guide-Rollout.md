# Story QLTY-01-S1 – Backend'de STYLE-BE-001 Yaygınlaştırma

- Epic: QLTY – Kod Kalitesi El Kitabı Yaygınlaştırma  
- Story Priority: 050  
- Tarih: 2025-11-19  
- Durum: Done

## Kısa Tanım

 Bu Story, backend servislerinde `STYLE-BE-001` ve backend proje/dizin yapısı (`docs/00-handbook/BACKEND-PROJECT-LAYOUT.md`) rehberlerine uyumu yaygınlaştırır; PR sürecinde stil kontrollerinin fiilen uygulanmasını sağlar.

## İş Değeri

- Backend kod kalitesini tek bir resmi standart altında toplar.  
- Yeni servis/endpoint açarken mimari ve klasör yapısının tutarlı olmasını sağlar.  
- Code review’lerde “stil tartışması” yerine rehbere referans verilmesini kolaylaştırır.  
- Teknik borcu azaltır ve yeni kişilerin koda adapte olmasını hızlandırır.

## Bağlantılar (Traceability Links)

- SPEC: (ileride gerekirse) `docs/05-governance/06-specs/SPEC-BACKEND-STYLE-BE-001-ROLLUP.md`  
- ACCEPTANCE: `docs/05-governance/07-acceptance/QLTY-01-S1-Backend-Style-Guide-Rollout.acceptance.md`  
- ADR: (şu an yok; gerekirse backend kalite stratejisi için ADR açılabilir)  
- STYLE GUIDE:  
  - `docs/00-handbook/STYLE-BE-001.md`  
  - `docs/00-handbook/BACKEND-PROJECT-LAYOUT.md`

## Kapsam

### In Scope
- Backend stil rehberi (`STYLE-BE-001`) ve backend proje/dizin yapısının yaygınlaştırılması.  
- PR template’e backend stil kontrolünün eklenmesi ve kullanılması.  
- İlgili giriş/durum dokümanlarında (handbook README) backend stil rehberine referans verilmesi.  

### Out of Scope
- Yeni backend runtime/stack seçimi (bu ADR konusu).  
- FE veya API stil rehberlerinin detayları (ayrı QLTY story’lerinde ele alınır).  

## Task Flow (Ready → InProgress → Review → Done)

```text
## Task Flow (Ready → InProgress → Review → Done)

+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
| Task                                                        | Ready        | InProgress    | Review       | Done        |
+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
| STYLE-BE-001 içeriğini oluştur ve referansları güncelle     | 2025-11-18   | 2025-11-18    | 2025-11-19   | 2025-11-19  |
| Backend layout dokümanını (BACKEND-PROJECT-LAYOUT) yaz      | 2025-11-18   | 2025-11-18    | 2025-11-19   | 2025-11-19  |
| Mimari/backlog dokümanlarında stil/layout referanslarını güncelle | 2025-11-19   | 2025-11-19    | 2025-11-19   | 2025-11-19  |
| PR template'e backend stil kontrol maddesini ekle           | 2025-11-19   | 2025-11-19    | 2025-11-19   | 2025-11-19  |
| Backend PR'ları için style-guides kullanımını tanımla       | 2025-11-19   | 2025-11-19    | 2025-11-21   | 2025-11-21  |
+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
```

## Güncel Durum (2025-11-21)

- PR template’teki `style-guides` maddesi backend için `STYLE-BE-001` + `BACKEND-PROJECT-LAYOUT` referanslarıyla net; backend PR’larında kontrol edilen rehberlerin kısa notla yazılıp işaretlendiği gözlemi yapıldı.
- Acceptance checklist’inin son maddesi karşılandı; Story DoD ve PROJECT_FLOW güncellemesi ile Story “Done”.

## Fonksiyonel Gereksinimler

1. Backend kod yazım kuralları tek bir dokümanda (`STYLE-BE-001`) toplanmış olmalı.  
2. Yeni backend servisi açma/dizin yapısı kuralları `BACKEND-PROJECT-LAYOUT` içinde tanımlanmış olmalı.  
3. PR template'te backend stil rehberine açık referans bulunmalı ve backend PR’ları için kullanılmalı.  
4. Giriş/durum dokümanlarında backend stil/layout rehberleri kanonik referans olarak gösterilmeli.  

## Non-Functional Requirements

- Performance: Dokümanlar kısa, okunabilir ve “Az ama Öz” olmalı; gereksiz tekrar içermemeli.  
- Security: Stil rehberi, logging ve error handling bölümlerinde gizli bilgi loglamamaya vurgu yapmalı.  
- UX / A11y: Doküman başlıkları ve linkleri tıklanabilir ve taranabilir olmalı.

## İş Kuralları / Senaryolar

- “Yeni backend servisi açma” → Geliştirici önce `BACKEND-PROJECT-LAYOUT` ve `STYLE-BE-001` dokümanlarını okur, sonra servis dizinini ve kodu bu rehbere göre kurar.  
- “Backend PR açma” → PR sahibi PR template'te `style-guides` kutusunu işaretlemeden önce ilgili backend stil rehberine bakar.  

## Interfaces (API / DB / Event)

### API
- Bu Story doğrudan bir API endpoint’i tanımlamaz; var olan API’lerin stiline dokunan rehber sağlar.

### Database
- DB şeması değiştirilmez; yalnız doküman ve kod stilini etkiler.

### Events
- Herhangi bir event eklenmez; gerekirse ileride “lint / style check” sonuçları için telemetri ADR ile ele alınabilir.

## Acceptance Criteria

Bu Story için güncel acceptance kriterleri:  
`docs/05-governance/07-acceptance/QLTY-01-S1-Backend-Style-Guide-Rollout.acceptance.md`

## Definition of Done

- [x] `STYLE-BE-001` yayınlanmış ve ilgili dokümanlar güncellenmiş olmalı.  
- [x] `BACKEND-PROJECT-LAYOUT` dokümanı yayınlanmış olmalı.  
- [x] Handbook README backend stil/layout referanslarıyla güncellenmiş olmalı.  
- [x] PR template’te backend stil rehberine atıf yapan `style-guides` checkbox’ı aktif kullanılmalı ve backend PR’larında düzenli olarak işaretleniyor olmalı.  
- [x] `PROJECT_FLOW.md` üzerinde Son Durum bu Story için ✔ Tamamlandı olarak işlenmiş olmalı.

## Notlar

- Gelecekte backend tarafında ayrı domain bazlı stil ekleri gerekirse, STYLE-BE-001’in versiyonu artırılarak güncellenecektir.

## Dependencies
- `docs/00-handbook/AGENT-CODEX.md`

## Risks
- Rehber çok uzun ve karmaşık hale gelirse ekip okumakta zorlanabilir; 1 sayfa kuralına dikkat edilmelidir.  

## Flow / Iteration İlişkileri
| Flow ID   | Durum    | Not                    |
|-----------|---------|------------------------|
| Flow-01   | Planned | İlk yaygınlaştırma     |

## İlgili Artefaktlar
- `docs/00-handbook/STYLE-BE-001.md`  
- `docs/00-handbook/BACKEND-PROJECT-LAYOUT.md`  
  - `docs/00-handbook/AGENT-CODEX.md`

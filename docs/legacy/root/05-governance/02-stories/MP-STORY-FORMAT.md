# MP-STORY-FORMAT – Story Doküman Formatı

Bu dosya, `docs/05-governance/02-stories/*.md` altındaki tüm Story dokümanlarının **standart formatını** tanımlar.  
Yeni bir Story yazılırken veya mevcut Story güncellenirken bu format izlenir.

---

## Kullanım Akışı (Flow)

Yeni bir Story yazma veya mevcut Story’yi refactor etme akışı:

1. İlgili Epic’i ve önceliği `docs/05-governance/01-epics/*.md` ve akış görünümü `docs/05-governance/PROJECT_FLOW.md` üzerinden belirle.  
2. Story ID’yi seç (örn. `E02-S02`) ve `docs/05-governance/02-stories/` altında dosya adını netleştir:
   - Örn. `E02-S02-Grid-UI-Kit-SSRM.md`.  
3. Story dosyasını oluştur veya güncelle:
   - Üst meta bloğunu bu MP’de tanımlanan formata göre hizala (Epic, Story Priority, Tarih, Durum).  
   - Kısa Tanım, İş Değeri, Bağlantılar ve Kapsam bölümlerini doldur.  
4. Task Flow tablosunu oluştur:
   - Story’yi bitirmek için gereken işleri task’lara böl.  
   - Tarihleri Ready/InProgress/Review/Done sütunlarına akış kuralına göre işle.  
5. Fonksiyonel ve Non‑functional gereksinimleri, iş kuralları ve arayüzleri (API/DB/Event) Story seviyesinde netleştir.  
6. Acceptance ve DoD:
   - Acceptance için ilgili `docs/05-governance/07-acceptance/<ACCEPTANCE_ID>.md` dosyasına referans ver (isim serbest; Story ID’li veya `AT-<MODULE>-NN` olabilir).  
   - DoD checklist’ini doldur; normatif kuralları (ADR, agents, style guide) referans al.  
7. PROJECT_FLOW güncellemesi:
   - Story’nin `Durum:` alanıyla uyumlu olacak şekilde `docs/05-governance/PROJECT_FLOW.md` içindeki tablo ve listeleri güncelle.
8. Mevcut mimariyi dikkate al:
   - Story kapsamını yazmadan önce ilgili mimari ve API dokümanlarını gözden geçir (`docs/01-architecture/**/*`, `docs/03-delivery/api/*.md`); Story bu dokümanlarla uyumlu olmalı veya gerekliyse yeni ADR/SPEC ile revizyon önerisini içermelidir.
9. İsimlendirme disiplini:
   - Story ID (`E0X-SYY`) yalnızca Story dokümanının dosya adında zorunludur; yeni SPEC veya ACCEPTANCE dosya adlarında tekrar kullanmak zorunlu değildir ve tercih edilmez.  

---

## Üst Başlık ve Meta Bilgiler

```md
# Story E??-S?? – <Story Başlığı>

- Epic: E?? – <Epic Adı>  
- Story Priority: NNN  
- Tarih: YYYY-AA-GG  
- Durum: Ready / In Progress / Review / Done  
- Modüller / Servisler: frontend-shell, api-gateway, permission-service (liste)
```

- `Epic:` ilgili epic ID + adı.  
- `Story Priority:` tek gerçek SP değeri (sadece Story’de tutulur).  
- `Tarih:` Story’nin ilk açıldığı veya önemli revizyon tarihi.  
- `Durum:` PROJECT_FLOW ile uyumlu olmalı (Ready / In Progress / Review / Done).

---

## 1. Kısa Tanım

Kullanıcı açısından kısa, net özet:

```md
## Kısa Tanım

Bu Story, <kullanıcı ihtiyacı> için <özellik/akış> sağlar.
```

---

## 2. İş Değeri

İş/hedef değeri mermilerle:

```md
## İş Değeri

- …  
- …  
```

---

## 3. Bağlantılar (Traceability Links)

Her Story aşağıdaki bağlantıları içerir:

```md
## Bağlantılar (Traceability Links)

- SPEC: (varsa `docs/05-governance/06-specs/...`)  
- ACCEPTANCE: `docs/05-governance/07-acceptance/<ACCEPTANCE_ID>.md`  
- ADR: ADR-XXX (ilgili mimari kararlar)  
- STYLE GUIDE: `docs/00-handbook/NAMING.md`
```

---

## 4. Kapsam (Scope)

```md
## Kapsam

### In Scope
- …

### Out of Scope
- …
```

- In Scope: Bu Story’de yapılacak işlerin listesi.  
- Out of Scope: Bilinçli olarak kapsam dışı bırakılan konular.

---

## 5. Task Flow (Ready → InProgress → Review → Done)

Her Story, task seviyesinde akışı ASCII tablo ile gösterir:

```md
## Task Flow (Ready → InProgress → Review → Done)

```text
+--------------+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
| Modül/Servis | Task                                                        | Ready        | InProgress    | Review       | Done        |
+--------------+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
| frontend     | Task 1 açıklaması                                           | YYYY-AA-GG   |               |              |             |
| api-gateway  | Task 2 açıklaması                                           | YYYY-AA-GG   | YYYY-AA-GG    |              |             |
| …            | …                                                           |              |               |              |             |
+--------------+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
```

**Kural:**
- İlk tarih “Ready” → görev hazır  
- İkinci tarih “InProgress” → çalışma başladı  
- Üçüncü tarih “Review” → kod kontrol aşamasında  
- Son tarih “Done” → tamamen tamamlandı  
- Soldaki sütun dolmadan sağdaki sütun doldurulmaz (Ready → InProgress → Review → Done).
- Modül/Servis sütunu AGENT-CODEX §6.3.1’deki “Nerelere değecek?” adımını kanıtlar; her satır ilgili servisle eşleşmelidir.
```

---

## 6. Fonksiyonel Gereksinimler

İş kurallarını maddeler halinde tanımlar:

```md
## Fonksiyonel Gereksinimler

1. …  
2. …  
```

---

## 7. Non-Functional Requirements

Performans, güvenlik, UX, i18n vb. beklentiler:

```md
## Non-Functional Requirements

- Performance: …  
- Security: …  
- UX / A11y: …  
```

---

## 8. İş Kuralları / Senaryolar

Tipik akışları metinle anlatır:

```md
## İş Kuralları / Senaryolar

- “Senaryo adı” → …  
- “Alternatif akış” → …  
```

---

## 9. Interfaces (API / DB / Event)

```md
## Interfaces (API / DB / Event)

### API
- `GET /api/...` – açıklama

### Database
- İlgili tablolar ve notlar

### Events
- `event.name` – tetikleyici / tüketici
```

---

## 10. Acceptance Criteria

Story içinden acceptance dosyasına referans verilir:

```md
## Acceptance Criteria

Bu Story için güncel acceptance kriterleri:  
`docs/05-governance/07-acceptance/<ACCEPTANCE_ID>.md`
```

Detaylı test maddeleri acceptance dosyasında tutulur; Story’de tekrar edilmez.

---

## 11. Definition of Done

Tüm Story’lerde benzer DoD checklist yapısı kullanılır:

```md
## Definition of Done

- [ ] İlgili SPEC maddeleri (varsa) karşılanmış olmalı.  
- [ ] Acceptance dosyasındaki tüm maddeler sağlanmış olmalı.  
- [ ] İlgili ADR kararları uygulanmış olmalı.  
- [ ] Kod, `docs/00-handbook/NAMING.md` ve stil kurallarına uyumlu olmalı.  
- [ ] Kod review’dan onay almış olmalı.  
- [ ] İlgili testler (unit/integration/e2e) yeşil olmalı.  
- [ ] `docs/05-governance/PROJECT_FLOW.md` üzerinde Son Durum ✔ Done olarak işlenmiş olmalı.
```

Gerekirse domain’e özel ek DoD maddeleri eklenebilir.

---

## 12. Notlar

Serbest metin alanı:

```md
## Notlar

- Gelecek fazlar, riskler, TODO’lar…
```

---

## 13. Dependencies / Risks / Flow İlişkileri / Artefaktlar

Son bloklarda aşağıdaki başlıklar kullanılır:

```md
## Dependencies
- ADR-XXX – …  
- Diğer Story veya sistem bağımlılıkları

## Risks
- …  

## Flow / Iteration İlişkileri
| Flow ID   | Durum    | Not |
|-----------|---------|-----|
| Flow-02   | Planned | …   |

## İlgili Artefaktlar
- İlgili ADR, architecture, frontend/backend rehberleri
```

Bu format, tüm Story’ler için **tek kaynaklı ve izlenebilir** bir yapıyı garanti eder;  
SPEC → STORY → Task Flow → CODE → ACCEPTANCE → PROJECT_FLOW zinciri bu şablon üzerinden çalışır.

# MP-SPEC-FORMAT – Teknik Spec Doküman Formatı

Bu dosya, `docs/05-governance/06-specs/*.md` altındaki **feature / teknik spesifikasyon** dokümanları için standart formatı tanımlar.  
Yeni bir SPEC yazılırken veya mevcut SPEC güncellenirken bu format doğrudan kullanılır (ayrı şablon dosyası yoktur).

---

## Kullanım Akışı (Flow)

Yeni bir SPEC gerektiğinde (iki ana trigger vardır) izlenecek adımlar:

1. Trigger durumunu belirle:
   - a) Bir Story’de “SPEC: (Henüz yok…)” deniyorsa  
   - b) Bir ADR “Accepted” oldu ve somut bir implementasyon (yeni servis, API, CLI, pipeline, UI akışı vb.) tarif ediyorsa
2. İlgili Story veya ADR’yi aç:
   - Story: `docs/05-governance/02-stories/<StoryId>.md`  
   - ADR: `docs/05-governance/05-adr/ADR-0XX-....md`
3. Spec ihtiyacını netleştir: Hangi modül / konu için teknik tasarım yazılacak (API, CLI, pipeline, veri modeli vb.).  
4. `docs/05-governance/06-specs/` altında yeni bir dosya oluştur:
   - İsim: `SPEC-<STORY-ID>-<kısa-konu>.md` (örn. `SPEC-E01-S05-identity-login.md`).  
5. Yeni dosyaya:
   - Üst meta bloğunu bu dokümandaki örnekten kopyala ve doldur (Başlık, Versiyon, Tarih, ADR/ACCEPTANCE/STORY linkleri).  
   - Aşağıdaki “Bölümler (Zorunlu Yapı)” başlıklarını sırasıyla ekle ve içeriklerini doldur.  
6. Normatif kuralları yazarken:
   - Güvenlik / uyum için `docs/agents/*.md` ve ilgili ADR’leri referans al; SPEC’te kural icat etme, uygulamayı tarif et.  
7. SPEC tasarımını tamamlayıp ekiple gözden geçir:
   - “Accepted” olacak kadar netleştiğinde ilgili ekipten onay al.
8. SPEC kabul edildikten sonra:
   - Henüz yoksa, bu SPEC’in implementasyonunu taşıyacak en az bir Story oluştur (`docs/05-governance/02-stories/` altında, `MP-STORY-FORMAT.md`’e göre).
   - Aynı Story için `docs/05-governance/07-acceptance/<ACCEPTANCE_ID>.md` şeklinde bir acceptance dokümanı oluştur ve acceptance kriterlerini buraya yaz (isim serbest; Story ID’li veya `AT-<MODULE>-NN` olabilir, Story yalnızca bu dosyaya link verir).
   - Story’den tetiklendiyse: İlgili Story’de `Bağlantılar (Traceability Links)` altındaki `SPEC:` alanını bu dosyaya güncelle.  
   - ADR’den tetiklendiyse: İlgili ADR’de “İlgili Dokümanlar (Traceability)” altına bu SPEC’i ekle.
   - Gerekirse ilgili runbook dokümanlarıyla zinciri tamamla.

---

## Üst Başlık ve Meta Bilgiler

Her SPEC dosyası aşağıdaki meta yapıyı takip eder:

```md
# SPEC-<STORY-ID>-<KISA-KONU>
**Başlık:** <Modül / Konu Adı>  
**Versiyon:** v1.0  
**Tarih:** YYYY-AA-GG  

**İlgili Dokümanlar:**  
- EPIC: docs/05-governance/01-epics/E0X_<Epic-Name>.md  
- ADR: docs/05-governance/05-adr/ADR-<MODULE>-NNN.md  
- ACCEPTANCE: docs/05-governance/07-acceptance/<ACCEPTANCE_ID>.md  
- STORY: docs/05-governance/02-stories/E0X-SYY-<Story-Name>.md  
- STYLE GUIDE: docs/00-handbook/NAMING.md  

**Etkilenen Modüller / Servisler:**  

| Modül/Servis  | Açıklama / Sorumluluk | İlgili ADR |
|---------------|-----------------------|------------|
| frontend-shell| Silent-check-sso akışı| ADR-003    |
| api-gateway   | Audience doğrulaması   | ADR-009    |
```

- Versiyon ve tarih değiştikçe güncellenir.  
- EPIC / ADR / ACCEPTANCE / STORY bağlantıları traceability için zorunludur ve tıklanabilir dosya yolu (veya göreli Markdown linki) olarak yazılmalıdır.  
- `SPEC_INDEX.md` gibi indeks tablolarında SPEC ID hücresi, yalnızca ID metnini ve göreli dosya linkini içerecek şekilde yazılır: `[SPEC-E01-S05-identity-login](SPEC-E01-S05-identity-login.md)`; aynı ID veya dosya adı satırda ikinci kez tekrar edilmez.
- Modül tablosu, AGENT-CODEX §6.3.1 kapsamında “Nerelere değecek?” adımının kanıtıdır; boş bırakılamaz.

---

## Bölümler (Zorunlu Yapı)

### 1. Amaç (Purpose)

Kısa ve net problem tanımı:

```md
# 1. Amaç (Purpose)
Bu spesifikasyonun amacını tanımlar.  
Bu özellik / API / modül kullanıcıya veya sisteme ne sağlar?
```

### 2. Kapsam (Scope)

```md
# 2. Kapsam (Scope)

### Kapsam içi
- …

### Kapsam dışı
- …
```

### 3. Tanımlar (Definitions)

Teknik terim ve kısaltmalar:

```md
# 3. Tanımlar (Definitions)

- JWT → JSON Web Token  
- DTO → Data Transfer Object  
```

### 4. Kullanıcı Senaryoları (User Flows)

Özelliğin kullanıcı gözünden akışı:

```md
# 4. Kullanıcı Senaryoları (User Flows)

- Başarılı senaryo  
- Hatalı senaryo  
- Edge case senaryolar  
```

### 5. Fonksiyonel Gereksinimler (Functional Requirements)

```md
# 5. Fonksiyonel Gereksinimler (Functional Requirements)

**FR-<KOD>-01:** …  
**FR-<KOD>-02:** …  
```

### 6. İş Kuralları (Business Rules)

```md
# 6. İş Kuralları (Business Rules)

**BR-<KOD>-01:** …  
**BR-<KOD>-02:** …  
```

### 7. Veri Modeli (Data Model)

```md
# 7. Veri Modeli (Data Model)

## 7.1. Veritabanı modeli
| Alan | Tip | Zorunlu | Açıklama |
|------|-----|---------|----------|
| …    | …   | …       | …        |

## 7.2. Request/Response DTO
```json
{ ... }
```
```

### 8. API Tanımı (API Spec)

```md
# 8. API Tanımı (API Spec)

## 8.1. Endpoint
- Method: …  
- Path: …  
- Auth: …  
- Content-Type: …  

## 8.2. Request Body
```json
{ ... }
```

## 8.3. Successful Response
```json
{ ... }
```

## 8.4. Error Responses
- 400 – …  
- 401 – …  
```

### 9. Validasyon Kuralları (Validation Rules)

Alan bazlı validation:

```md
# 9. Validasyon Kuralları (Validation Rules)

Alan X:
- zorunlu  
- format …  
```

### 10. Hata Kodları (Error Codes)

```md
# 10. Hata Kodları (Error Codes)

| Kod | HTTP | Açıklama |
|-----|------|----------|
| …   | …    | …        |
```

### 11. Non-Fonksiyonel Gereksinimler (NFR)

```md
# 11. Non-Fonksiyonel Gereksinimler (NFR)

Performans:
- …

Güvenlik:
- …

Rate Limit:
- …

Audit:
- …
```

### 12. İzlenebilirlik (Traceability)

```md
# 12. İzlenebilirlik (Traceability)

Bu spekten türeyen dokümanlar:
- Story: STORY-XXX  
- Acceptance: AT-XXX  
- ADR: ADR-XXX  
```

---

## Normatif Kuralların Kaynağı

- SPEC dokümanları **yeni kural icat etmez**; kuralların ana kaynağı:
  - Güvenlik / uyum: `docs/agents/*.md`  
  - Mimari kararlar: `docs/05-governance/05-adr/*.md`  
  - Mevcut mimari ve API sözleşmeleri: `docs/01-architecture/**/*`, `docs/03-delivery/api/*.md`  
- SPEC, bu kuralları belirli bir modül/özellik bağlamında **uygulayan** dokümandır;  
  mevcut mimari dokümanlarla çelişiyorsa, ilgili mimari doc’lar da güncellenmeli veya yeni ADR ile revizyon kararı alınmalıdır.

---

## Örnekler

- Boş şablon / referans:  
- Agent odaklı spesifikasyon örneği:  
  - `docs/agents/10-keycloak-access-request-cli.md` (SPEC-AGENT formatında doldurulmuş örnek)

Bu MP, SPEC formatını standardize eder;  
`MP-WORKFLOW` (ileride eklenecek) ise “hangi komutta hangi SPEC açılır?” sorusunu yanıtlayacaktır.

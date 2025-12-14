# MP-AGENT-FORMAT – Agent Doküman Formatı

Bu dosya, agent ekosistemi için iki farklı doküman tipinin **standart formatını** tanımlar:

- LLM davranış dokümanları: `AGENT-*.md`
- Çalışan runtime agent/spec dokümanları: `SPEC-AGENT-*.md`

`MP-STORY-FORMAT.md` ve `MP-SPEC-FORMAT.md` ile aynı mantıkta, **formatı** tanımlar;  
kuralların kendisi ilgili agent veya spec dokümanlarında tutulur.

---

## 1. Kullanım Akışı (Flow)

Agent ile ilgili yeni bir doküman yazarken genel akış:

1. **Agent türünü belirle**
   - Sadece LLM davranışını/prompt kurallarını tanımlayacaksan → `AGENT-*.md`
   - Çalışan bir süreç/CLI/worker/cron vb. teknik tasarımı için → `SPEC-AGENT-*.md`
2. **Konum ve isimlendirme**
   - LLM agent dosyaları, yönettikleri alanın kök klasöründe tutulur:
     - Governance: `docs/05-governance/AGENT-GOVERNANCE.md`
     - Spec: `docs/05-governance/06-specs/AGENT-SPEC.md`
     - Security/Compliance: `docs/agents/AGENT-SECURITY.md`
   - Runtime agent/spec dokümanları:
     - Genellikle `docs/05-governance/06-specs/` veya ilgili domain klasöründe
     - İsim: `SPEC-AGENT-<NAME>.md`
3. **Format seçimi**
   - LLM davranış dokümanı yazıyorsan → Bölüm 2’deki `AGENT-*.md` formatını uygula.
   - Runtime agent/spec yazıyorsan → Bölüm 3’teki `SPEC-AGENT-*.md` formatını uygula.
4. **Referansları bağla**
   - Story / Epic / ADR / Acceptance dosyalarına traceability linkleri ekle.
   - Security/compliance için `docs/agents/*.md` altında ilgili normatif kural dosyalarına atıf yap.
5. **Güncelleme**
   - Agent’ı genişlettiğinde, önce ilgili `AGENT-*.md` dosyasını, sonra gerekiyorsa `SPEC-AGENT-*` dokümanını güncelle.

---

## 2. LLM Agent Doküman Formatı (`AGENT-*.md`)

LLM’in belirli bir domain’de nasıl davranacağını tanımlayan dosyalar:

- Örnekler:
  - `docs/agents/AGENT-SECURITY.md`
  - (gelecekte) `docs/05-governance/AGENT-GOVERNANCE.md`
  - (gelecekte) `docs/05-governance/06-specs/AGENT-SPEC.md`

Her `AGENT-*.md` dosyası asgari şu yapıyı izler:

```md
# AGENT-<KONU>

## Amaç (Purpose)
- Bu agent hangi domain’i yönetir?
- Hangi işleri koordine eder veya kolaylaştırır?

## Kapsam (Scope)
- Kapsam içi:
  - İlgili klasörler/dosyalar (örn. `docs/05-governance/02-stories/*.md`)
- Kapsam dışı:
  - Bu agent’in yönetmediği alanlar (örn. `docs/01-architecture/**`)

## Zorunlu Referanslar (Required Docs)
- Bu agent çalışırken MUTLAKA okunması gereken dokümanlar:
  - MP formatları (örn. `MP-STORY-FORMAT.md`, `MP-SPEC-FORMAT.md`, `MP-AGENT-FORMAT.md`)
  - İlgili security/compliance dokümanları (`docs/agents/*.md`)
  - İlgili ADR’ler

## Çalışma Kuralları (How the Agent Should Behave)
- LLM bu alanda nasıl hareket eder?
- Hangi kuralları yeniden tanımlamaz, sadece uygular?
- Hangi dokümanları tek gerçek kaynak (source of truth) kabul eder?
```

Bu format, yeni `AGENT-*.md` dosyaları için kanonik şablondur.

---

## 3. Runtime Agent / CLI / Worker Formatı (`SPEC-AGENT-*.md`)

Çalışan bir ajan (CLI, cron job, worker, pipeline adımı, otomasyon aracı vb.) için teknik tasarım dokümanları `SPEC-AGENT-*.md` formatını izler.

Temel iskelet:

```md
# SPEC-AGENT-<NAME>
**Başlık:** <Agent Adı>  
**Versiyon:** v1.0  
**Tarih:** YYYY-AA-GG  

**İlgili Dokümanlar:**  
- STORY: STORY-XXX  
- ADR: ADR-AGENT-XXX  
- ACCEPTANCE: AT-AGENT-XXX  
- STYLE GUIDE: `docs/00-handbook/NAMING.md`  

---

## 1. Amaç (Purpose)
Bu agent’ın çözmek istediği problem nedir?  
İnsan yerine hangi otomatik işlemleri yapar?

## 2. Görev Tanımı (Responsibilities)
- …  
- …  

## 3. Tetikleyiciler (Triggers)
- Cron zamanlayıcı  
- Event / Webhook  
- Queue mesajı  
- Manuel CLI / UI tetikleme  

## 4. Girdi–Çıktı (Input / Output)
### Input:
- Parametreler, konfigürasyon, env değişkenleri

### Output:
- Üretilen artefaktlar (ticket, event, dosya, log vb.)

## 5. İş Kuralları (Business Rules)
**BR-AGENT-01:** …  
**BR-AGENT-02:** …  

## 6. Süreç Akışı (Process Flow)
1. Input okunur  
2. Validasyon yapılır  
3. İş kuralları uygulanır  
4. API / DB / Queue yazılır  
5. Audit log oluşturulur  
6. Başarılı → output üret  
7. Hatalı → retry veya DLQ  

## 7. Validasyon Kuralları (Validation)
- Zorunlu alanlar  
- Veri türleri  
- Format / aralık kontrolleri  

## 8. Performans & NFR
- Maksimum çalışma süresi  
- Retry davranışları  
- Rate limit / kaynak sınırları  

## 9. Güvenlik
- Yetki kontrolleri  
- Token/secret yönetimi  
- Input sanitization  
- Hassas verilerin maskelenmesi  

## 10. İzlenebilirlik (Observability)
- Log formatı  
- Metrics / dashboard bağlantıları  
- Alarm kuralları  

## 11. Traceability
- İlgili Story / ADR / Acceptance dokümanları  

## 12. Riskler
- Bilinen riskler ve etkileri  

## 13. Notlar
- Gelecek geliştirmeler, TODO’lar
```

Örnek uygulanmış doküman:
- `docs/agents/10-keycloak-access-request-cli.md`  
  → `SPEC-AGENT-KEYCLOAK-ACCESS-CLI` formatına göre doldurulmuş örnek.

---

## 4. Diğer Dokümanlarla İlişki

- **AGENT-CODEX:**  
  - Hangi alan için hangi `AGENT-*` ve `MP-*` dosyalarına bakılacağını küresel olarak tanımlar.
- **AGENT FORMAT (bu dosya):**  
  - Yeni `AGENT-*.md` dosyalarının nasıl tasarlanacağını tarif eder; bu MP dokümanı formatı netleştirir.
- **MP-STORY-FORMAT & MP-SPEC-FORMAT:**  
  - Story ve Spec yazımında agent’ların nasıl referans verileceğini belirler (Traceability Links, DoD vb.).

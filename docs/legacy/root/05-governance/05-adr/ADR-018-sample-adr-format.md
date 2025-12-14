# ADR-DOCS-018 – Örnek ADR (MP-ADR-FORMAT Uygulaması)

**Durum (Status):** Proposed  
**Tarih:** 2025-11-19  

**İlgili Dokümanlar (Traceability):**
- MP-ADR: `docs/05-governance/05-adr/MP-ADR-FORMAT.md`
- Örnek Story: `docs/05-governance/02-stories/E01-S05-Login.md`
- Örnek Spec: `docs/05-governance/06-specs/MP-SPEC-FORMAT.md` içindeki meta/blok yapısı
- Agents / Security: `docs/agents/MP-AGENT-FORMAT.md`, `docs/agents/AGENT-SECURITY.md`
- İlgili doküman (Docs/MP formatları): `docs/05-governance/05-adr/MP-ADR-FORMAT.md`, `docs/05-governance/02-stories/MP-STORY-FORMAT.md`, `docs/05-governance/06-specs/MP-SPEC-FORMAT.md`, `docs/agents/MP-AGENT-FORMAT.md`

---

# 1. Bağlam (Context)

- Bu ADR, `MP-ADR-FORMAT.md` kullanımını göstermek için oluşturulmuştur.
- Gerçek bir prod mimari kararı temsil etmez; sadece örnek bir yapıdır.
- Ekip, yeni ADR yazarken kullanacağı bölümleri ve başlıkları somut görmek istemiştir.

---

# 2. Karar (Decision)

1. Bu örnek ADR, `MP-ADR-FORMAT` dokümanında tanımlanan ADR iskeletini uygular.
2. Bu ADR, gelecekte silinmek yerine “Rejected” veya “Deprecated” durumuna çekilerek yalnızca eğitim/örnek amacıyla korunur.
3. Yeni gerçek ADR’ler, burada gösterilen yapıyı takip eder; içerik anlamında bu ADR’ye referans vermez.

---

# 3. Alternatifler (Alternatives)

Bu örnek ADR için gerçek teknik alternatifler listelenmemiştir;  
ama format gereği alan ayrılmıştır.

### 3.1. Seçenek A  
- Sadece TEMPLATE veya MP dokümanları ile yetinmek.  

Artıları:  
- Daha az doküman sayısı.  

Eksileri:  
- Somut doldurulmuş örnek olmadan formatın anlaşılması daha zor.  

### 3.2. Seçilen Alternatif  
- MP + TEMPLATE + örnek ADR kombinasyonu kullanmak.

---

# 4. Gerekçeler (Rationale)

- MP-ADR-FORMAT şablonu teorik olarak tanımlıdır; ekip, pratikte doldurulmuş bir örnek görmek istemektedir.
- TEMPLATE dokümanlarına ek olarak, gerçek path ve linklerle dolu bir ADR’nin okunması formatın daha hızlı içselleştirilmesini sağlar.

---

# 5. Sonuçlar (Consequences)

### 5.1. Olumlu Sonuçlar
- Yeni ADR yazan herkes bu dosyanın yapısını kopyalayarak hızlıca başlayabilir.
- Eğitim/onboarding sırasında tek bir dosya üzerinden format anlatılabilir.

### 5.2. Olumsuz Sonuçlar
- Repo analizlerinde bu ADR’nin “örnek” olduğu bilinmeli, roadmap veya içerik analizi yapılırken filtrelenmelidir.

---

# 6. Uygulama Detayları (Implementation Notes)

- Bu ADR, sadece dokümantasyon seviyesinde bir örnektir; kod değişikliğine karşılık gelmez.
- Gerektiğinde format güncellendiğinde ilk güncellenecek ADR’lerden biri olmalıdır.

---

# 7. Durum Geçmişi (Status History)

| Tarih       | Durum    | Not                                          |
|-------------|----------|-----------------------------------------------|
| 2025-11-19  | Proposed | Örnek ADR formatı olarak ilk sürüm oluşturuldu |

---

# 8. Notlar

- Bu ADR, gerçek bir mimari kararı temsil etmez; yalnızca format ve stil örneğidir.
- İleride gerekirse, daha güncel bir örnek ADR ile güncellenebilir veya Durum alanı “Rejected” / “Deprecated” olarak değiştirilebilir.

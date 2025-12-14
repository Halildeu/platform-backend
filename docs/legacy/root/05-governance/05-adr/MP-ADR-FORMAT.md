# MP-ADR-FORMAT – Mimari Karar Kaydı (ADR) Formatı

Bu dosya, `docs/05-governance/05-adr/*.md` altındaki tüm **Architecture Decision Record (ADR)** dokümanlarının standart formatını tanımlar.  
Yeni bir ADR yazılırken veya mevcut bir ADR refactor edilirken bu format izlenir.

---

## 1. Kullanım Akışı (Flow)

Yeni bir ADR yazma akışı:

1. **Konu ve ID seçimi**
   - Konuyu netleştir: tek bir mimari kararı anlatmalı.
   - İlgili mevcut mimari dokümanları gözden geçir: `docs/01-architecture/**/*`, `docs/03-delivery/api/*.md` ve varsa ilgili runbook/agent dokümanları; ADR, bu mimariyi ya tamamlamalı ya da bilinçli olarak revize etmelidir (güncelleme ihtiyacı varsa not al).
   - ID: Sıralı numara kullan (`ADR-018`, `ADR-019` …).
   - Dosya adı: `ADR-0XX-konu-kisa-tanim.md`
2. **Üst başlık ve meta bloğunu oluştur**
   - `# ADR-<MODULE>-<NUMBER> – <Karar Başlığı>` formatını kullan.
   - Hemen altında Durum, Tarih ve İlgili Dokümanlar bloklarını doldur.
3. **Bölümleri sırayla doldur**
   - 1. Bağlam, 2. Karar, 3. Alternatifler, 4. Gerekçeler, 5. Sonuçlar, 6. Uygulama Detayları, 7. Durum Geçmişi, 8. Notlar.
4. **Traceability’yi bağla**
   - İlgili Epic, Story, Spec, Acceptance ve ADR’leri “İlgili Dokümanlar” altında belirt; tüm referanslar tıklanabilir repo yolu veya göreli Markdown linki olmalıdır.
   - `ADR_INDEX.md` gibi indeks tablolarında ADR ID hücresi yalnızca ID metnini ve göreli dosya linkini içerecek şekilde yazılır (`[ADR-012](ADR-012-permission-registry.md)`); aynı ID veya dosya adı satırda ikinci kez tekrar edilmez.
5. **Durum yönetimi**
   - Karar kabul edildiğinde Durum’u güncelle, Durum Geçmişi tablosuna satır ekle.
   - Başka bir ADR ile geçersiz kılınırsa Superseded satırını doldur.

---

## 2. Standart ADR İskeleti

Aşağıdaki iskelet tüm yeni ADR’ler için temel şablondur:

```md
# ADR-<MODULE>-<NUMBER> – <Karar Başlığı>

**Durum (Status):** Proposed / Accepted / Rejected / Deprecated / Superseded  
**Tarih:** YYYY-AA-GG  

**İlgili Dokümanlar (Traceability):**
- EPIC: docs/05-governance/01-epics/E0X_<Epic-Name>.md
- SPEC: docs/05-governance/06-specs/SPEC-<STORY-ID>-<kısa-konu>.md
- ACCEPTANCE: docs/05-governance/07-acceptance/<ACCEPTANCE_ID>.md
- STORY: docs/05-governance/02-stories/E0X-SYY-<Story-Name>.md
- İlgili ADR’ler: ADR-<MODULE>-YYY
- STYLE GUIDE: docs/00-handbook/NAMING.md

---

# 1. Bağlam (Context)

Bu kararı almamıza neden olan durum nedir?

- Hangi problem çözülmek isteniyor?
- Bu karar hangi modül/domain için?
- Sistem hangi kısıtlar altında?
- Hangi ihtiyaçlar / beklentiler var?
- İş gereksinimleri, performans, güvenlik vb. nasıl etkiliyor?

Bu bölüm “neden bir karara ihtiyaç duyduk?” sorusunu açıklar.

---

# 2. Karar (Decision)

Net ve sade biçimde:

- Ne yapmaya karar verildi?
- Hangi teknoloji / yöntem / strateji seçildi?
- Ne benimsendi, ne benimsenmedi?

**Örnek:**  
“Sistemin kimlik doğrulaması için JWT tabanlı stateless auth kullanılacaktır.”

---

# 3. Alternatifler (Alternatives)

Bu karardan önce değerlendirilen seçenekler:

### 3.1. Seçenek A  
Artıları:  
Eksileri:  

### 3.2. Seçenek B  
Artıları:  
Eksileri:  

### 3.3. Seçilen Alternatif  
Neden seçildi?  
Diğer seçeneklere göre hangi avantajları var?

Bu bölüm gelecekte “niye ötekini seçmedik?” sorusunun cevabıdır.

---

# 4. Gerekçeler (Rationale)

Bu karar neden doğru?

- Performans gerekçeleri  
- Güvenlik gerekçeleri  
- Operasyonel kolaylık  
- Kod bakım maliyeti  
- Ekip uzmanlığı  
- Mimarinin uzun vadeli stratejisi  

Bu bölüm kararın **arkasındaki mantığı** resmileştirir.

---

# 5. Sonuçlar (Consequences)

Kararın sistemdeki etkileri:

### 5.1. Olumlu Sonuçlar
- Avantaj 1  
- Avantaj 2  

### 5.2. Olumsuz Sonuçlar
- Dezavantaj 1  
- Dezavantaj 2  
- Oluşan teknik borç (varsa)

Bu bölüm kararın bedelini gösterir.

---

# 6. Uygulama Detayları (Implementation Notes)

- Bu karar hangi servis/modüllerde uygulanacak?
- Kod seviyesinde kritik noktalar?
- Kullanılacak kütüphaneler / paketler?
- Migration ihtiyacı var mı?
- Geçici çözümler (workaround) var mı?

---

# 7. Durum Geçmişi (Status History)

| Tarih       | Durum       | Not                                     |
|-------------|-------------|------------------------------------------|
| YYYY-AA-GG  | Proposed    | İlk taslak oluşturuldu                   |
| YYYY-AA-GG  | Accepted    | Karar ekip tarafından onaylandı          |
| YYYY-AA-GG  | Superseded  | ADR-<NUMBER> tarafından geçersiz kılındı |

---

# 8. Notlar

Ek bilgiler, riskler, ileri faz notları, öneriler.
```

Bu iskelet, `MP-STORY-FORMAT` ve `MP-SPEC-FORMAT` ile aynı seviyede,  
ADR’ler için tek ve tutarlı bir format sağlar.

---

## 3. Durum (Status) Değerleri

`Durum (Status)` alanı için kullanılacak sabit seçenekler:

- `Proposed` – Yeni yazılmış, tartışma aşamasındaki ADR.
- `Accepted` – Ekip tarafından onaylanmış ve uygulanmakta olan karar.
- `Rejected` – Tartışılmış ama uygulanmaması kararlaştırılmış seçenek.
- `Deprecated` – Hâlen sistemde izleri olsa da yeni işler için kullanılmaması gereken, zamanla kaldırılacak karar.
- `Superseded` – Başka bir ADR tarafından tamamen geçersiz kılınmış karar (ilgili ADR ID’si Status History’de belirtilir).

Her ADR’de **yalnızca bir** durum seçilir; iskeletteki satır örnek olarak tüm opsiyonları gösterir.*** End Patch ***!

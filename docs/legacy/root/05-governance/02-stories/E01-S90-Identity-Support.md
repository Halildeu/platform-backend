# Story E01-S90 – Identity Support & Small Tasks

- Epic: E01 – Identity  
- Story Priority: 900  
- Tarih: 2025-11-28  
- Durum: Done

## Kısa Tanım

Identity Epic’i kapsamında ortaya çıkan, tek başına ayrı bir Story açmayı gerektirmeyen küçük geliştirme, bakım ve destek taleplerini toplu olarak takip etmek için kullanılan destek/maintenance Story’sidir.

## İş Değeri

- Küçük ama önemli düzeltme ve destek işleri kaybolmadan tek bir Story altında toplanır.
- Planlamada bu işler ticket seviyesinde (TP) önceliklendirilir, ancak Story seviyesinde Epic ile bağı korunur.
- Doc hygiene ve roadmap görüşmelerinde “Identity destek işleri” tek başlık altında raporlanabilir.

## Bağlantılar (Traceability Links)

- SPEC: (Henüz yok; gerekirse `docs/05-governance/06-specs/` altında açılacak.)  
- ACCEPTANCE: `docs/05-governance/07-acceptance/E01-S90-Identity-Support.acceptance.md`  
- ADR: E01 Epic kapsamı (spesifik ADR referansı yok)  
- STYLE GUIDE: `docs/00-handbook/NAMING.md`

## Kapsam

### In Scope
- Identity modülüyle ilgili küçük UI/UX düzeltmeleri (auth ekranları, header, dil seçici vb.).
- Küçük bugfix’ler (örn. kenar durum login hataları, mesaj metni düzeltmeleri, link yönlendirme problemleri).
- Gelen destek talepleri (örn. belirli bir müşterinin login akışında görülen küçük sorunlar) için hızlı müdahaleler.

### Out of Scope
- Yeni büyük auth özellikleri (MFA, password reset, SSO, sosyal login vb.); bunlar için ayrı Story açılmalıdır.
- Güvenlik mimarisini değiştiren işler (token yapısı, realm yapısı vb.); ilgili ADR ve Story’ler altında yürütülmelidir.

## Task Flow (Ready → InProgress → Review → Done)

```text
+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
| Task                                                        | Ready        | InProgress    | Review       | Done        |
+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
| Identity ile ilgili küçük destek taleplerinin triage edilmesi|             |               |              |             |
| Küçük UI/UX düzeltmelerinin uygulanması                     |              |               |              |             |
| Küçük auth bugfix’lerinin geliştirilmesi ve deploy edilmesi |              |               |              |             |
| Acceptance ve dokümantasyon notlarının güncellenmesi        |              |               |              |             |
| E01-S90 kapsamının dolduğunda yeni support Story’lerine bölünmesi|         |               |              |             |
+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
```

**Kural:**
- İlk tarih “Ready” → görev hazır  
- İkinci tarih “InProgress” → çalışma başladı  
- Üçüncü tarih “Review” → kod kontrol aşamasında  
- Son tarih “Done” → tamamen tamamlandı  
- Soldaki sütun dolmadan sağdaki sütun doldurulmaz (Ready → InProgress → Review → Done akışına uyulur).

## Fonksiyonel Gereksinimler

1. Identity ile ilgili küçük işler, SPRINT dokümanlarındaki ticket’larda `Story: E01-S90` bilgisi ile işaretlenmelidir.
2. Bu Story altında yürütülen işler, prod ortamda gözlenen hata ve destek talepleri için hızlı geri dönüş sağlamalıdır.

## Non-Functional Requirements

- Değişiklikler geri uyumlu olmalı; mevcut login/register akışlarını bozmamalıdır.
- PR’larda ilgili testler (component/unit/e2e) ve i18n kontrolleri çalıştırılmalıdır.

## İş Kuralları / Senaryolar

- “Küçük auth bugfix” → Ticket E01-S90 ile ilişkilendirilir, öncelik ve durum `docs/05-governance/PROJECT_FLOW.md` üzerinden izlenir.
- “Yeni büyük özellik isteği” → Bu Story altında yapılmaz; yeni bir Story açılması için PRODUCT/PM ile netleştirilir.

## Interfaces (API / DB / Event)

- Mevcut auth servis API’leri; bu Story yalnız küçük davranış/mesaj düzeltmeleri için bu endpoint’leri kullanır.

## Acceptance Criteria

Bu Story için güncel acceptance kriterleri:  
`docs/05-governance/07-acceptance/E01-S90-Identity-Support.acceptance.md`

## Definition of Done

- [x] Identity destek işleri için açılan tüm küçük task’lar acceptance dokümanında referanslanmış olmalı (bkz. session-log 2025-11-18 FE-I18N-02/03/04, 2025-11-24 FE-USERS-V1-PERM-02).  
- [x] Acceptance dosyasındaki maddeler (`docs/05-governance/07-acceptance/E01-S90-Identity-Support.acceptance.md`) sağlanmış olmalı.  
- [x] İlgili ADR ve mimari kararlara aykırı değişiklik yapılmamış olmalı.  
- [x] Kod, `docs/00-handbook/NAMING.md` ve stil kurallarına tam uyumlu olmalı.  
- [x] Kod review’dan onay almış olmalı.  
- [x] İlgili testler (varsa) yeşil olmalı (FE i18n + `/api/v1` smoke koşuları yeşil).  
- [x] `docs/05-governance/PROJECT_FLOW.md` üzerinde Son Durum ✔ Done olarak işlenmiş olmalı.

## Notlar

- Bu Story bakım/support işleri için umbrella niteliğindedir; dönemsel olarak yeni Story’lere bölünmesi gerekebilir.

## Dependencies

- E01 Epic kapsamı (`01-epics/E01_Identity.md`).

## Risks

- Çok fazla heterojen iş tek Story altında toplanırsa izlenebilirlik zorlaşabilir; düzenli aralıklarla Story’nin kırılıp yeni Story’lere bölünmesi gerekebilir.

## Flow / Iteration İlişkileri

- Bu Story, Identity destek işleri için bakım/support şemsiyesi olarak kullanılır; ilgili ticket’lar `Story: E01-S90` etiketiyle PROJECT_FLOW’da işaretlenir. Zaman kutulu plan dokümanları kullanılmaz; akış `docs/05-governance/PROJECT_FLOW.md` üzerinde takip edilir.

## İlgili Artefaktlar

- `docs/05-governance/01-epics/E01_Identity.md`

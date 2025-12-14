# 07-Acceptance – Epic / Story / Proje Kabul Kayıtları

Bu klasör, Epic/Story/proje işleri için **tek kabul kaynağı**dır. Amaç:

- İşin “bitti” sayılması için gereken kriterleri (Checklist) tek yerde toplamak.
- Story dokümanlarını sade tutup, detaylı kabul maddelerini buraya taşımak.
- Legacy acceptance kayıtlarını da aynı kökün altında normalize etmek.

## Kullanım Kuralları

- **Tekil Story Acceptance**
  - Her Story için (gerekiyorsa) bir acceptance dosyası oluşturulur.
  - Dosya adı Story ID’sini içerebilir (`E0X-SYY-*.acceptance.md`) veya bağımsız bir ID kullanabilir (`AT-<MODULE>-NN.md`); yeni dokümanlar için tercih edilen yaklaşım Story ID’den bağımsız bir `<ACCEPTANCE_ID>` seçip dosya yolunu `docs/05-governance/07-acceptance/<ACCEPTANCE_ID>.md` şeklinde tıklanabilir yazmaktır.
  - Story dokümanında (`02-stories/*.md`) yalnızca acceptance dosyasına link verilir; ayrıntılı checklist acceptance dosyasında tutulur.

- **Legacy Acceptance**
  - Eski `roadmap-legacy/archive/acceptance/*.md` dokümanları bu klasöre taşındı.
  - Frontmatter içindeki `related_ticket` veya isim üzerinden Epic/Story ile ilişki kurulabilir.

- **Okuma Akışı**
  1. Epic/Story ne yapıyor? → `01-epics/` + `02-stories/`
  2. Nasıl yapıyoruz? → `06-specs/`
  3. Ne zaman bitti sayılır? → **`07-acceptance/`**

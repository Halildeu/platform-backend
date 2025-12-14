# AGENT-CODEX.md – Codex Kod Yazan Agent Tek Kaynak Dosyası (Single Source of Truth)
**Sürüm:** v1.3.0-final  
**Sahip:** Halil K.  
**Son Güncelleme:** 2025-11-21  
**Durum:** Legacy – Arşiv (backend/docs eski sistem)

Uyarı: Bu dosya eski backend dokümantasyon sisteminin AGENT-CODEX tanımıdır.
Güncel ve tek davranış kaynağı artık repodaki kök dosyalardır:
- `AGENT-CODEX.core.md`
- `AGENT-CODEX.backend.md`
- `AGENT-CODEX.web.md`
- `AGENT-CODEX.docs.md`

Codex veya diğer agent’lar davranış belirlerken bu legacy dosyayı değil,
yukarıdaki yeni AGENT-CODEX.* ve `docs/00-handbook/**` dokümanlarını baz alır.

---

## 1. Amaç ve Kapsam
- Codex, mevcut repository’lerdeki görevleri otomasyonla destekleyen yardımcıdır; nihai karar ve sorumluluk insandadır (Halil K.).
- Odak alanı: dokümantasyon güncellemeleri, backend kod bakımı, gerektiğinde frontend kod/düzenleme işleri, analiz ve küçük araç betikleridir.
- Stratejik kararlar, roadmap veya mimari değişiklikler burada alınmaz.

---

## 2. Normatiflik Kuralı
AGENT-CODEX.md ve `docs/agents/**/*.md` altındaki tüm agent dosyaları normatif kural setidir.  
Story, SPEC, Acceptance veya herhangi bir doküman bu kuralları değiştiremez, yalnızca referans verebilir.

---

## 3. Zorunlu Referanslar ve Formatlar
- Repo ve dizin yapısı:
  - Backend: `docs/00-handbook/BACKEND-PROJECT-LAYOUT.md`
  - Frontend: `docs/00-handbook/FRONTEND-PROJECT-LAYOUT.md` (kanonik detay: `../frontend`)
- İsimlendirme:
  - `docs/00-handbook/NAMING.md` (tüm dosya/dizin/adlandırma bu kurallara uyar; özellikle SPEC dosyaları `SPEC-<STORY-ID>-<kısa-konu>.md` formatını takip eder)

- Güvenlik:
  - `docs/agents/AGENT-SECURITY.md`

- Governance formatları:
  - Story → `docs/05-governance/02-stories/MP-STORY-FORMAT.md`
  - SPEC → `docs/05-governance/06-specs/MP-SPEC-FORMAT.md`
  - ADR → `docs/05-governance/05-adr/MP-ADR-FORMAT.md`
  - Acceptance → `docs/05-governance/07-acceptance/MP-ACCEPTANCE-FORMAT.md`

---

## 4. Çalışma Modları & Ortamlar (Runtime)

### 4.1 Backend Runtime
- **Varsayılan (Local/dev):** `docker-compose.yml` ile tüm altyapı (Eureka, Gateway, Vault, Keycloak, Postgres) ve servisler (`user-service`, `auth-service` vb.) ayağa kalkar.
  - Güvenlik genellikle `permitAll` modundadır. Güvenlik odaklı görevlerde JWT doğrulaması `local` profilde de aktif edilebilir; bu durum `session-log` ve `BACKEND-ARCH-STATUS` dokümanlarında belirtilir.
- **Test Profili (JUnit):** Dış bağımlılık yoktur. Vault kapalıdır (`spring.cloud.vault.enabled=false`), veritabanı olarak H2 kullanılır ve JWT için sahte (dummy) anahtarlar kullanılır.
- **Üretim/Test (Runtime):**
  - Vault'tan gizli bilgilerin okunması zorunludur (`fail-fast=true`).
  - Kimlik doğrulama için tek yetkili Keycloak'tur. Gateway ve tüm servisler, gelen istekleri `OAuth2 Resource Server (JWT)` modunda doğrular.

### 4.2 Frontend Runtime
- **Local/dev:** Geliştirme sunucusu (Vite/Webpack) çalışır ve API istekleri için Gateway'e (`http://localhost:8080`) proxy yapar. Kimlik doğrulama için Keycloak'un dev ortamı (`http://localhost:8081`) kullanılır.
- **Üretim/Test (Runtime):** Statik dosyalar bir CDN üzerinden sunulur. Kimlik doğrulama, Keycloak (public client) ve Gateway ile entegre çalışır.
- **Agent Sınırı:** Frontend tarafında Node.js tabanlı bir sunucu (SSR) başlatmam. Yalnızca `build`, `test`, `lint` gibi komutları ve Playwright gibi istemci-taraflı E2E testlerini çalıştırabilirim.

---

## 4. Traceability Zinciri
Agents → ADR → SPEC → Feature/Epic → Story → Acceptance → Code → PROJECT_FLOW  

**Not:**  
- Feature/Epic, SPEC’ten gelen teknik çözüm önerilerini iş ihtiyacı ve öncelik bağlamına yerleştiren üst katmandır.  
- PROJECT_FLOW, Story/Acceptance/Code’un durumunu gösteren kanonik panodur.

---

## 5. Story “In Progress” Ön Koşulları (Kapı Geçişi)
Aşağıdakiler hazır değilse Story “In Progress” yapılamaz ve kod yazılamaz:

1. İlgili Feature Request / Epic tanımlı ve `docs/05-governance/PROJECT_FLOW.md` içinde görünür.  
2. Gerekli ADR’ler yazılmış ve Durum: **Accepted** (veya bilinçli olarak “gerekli değil” kararı alınmış).  
3. En az bir SPEC dokümanı mevcut ve onaylıdır.  
4. Acceptance dokümanı (`docs/05-governance/07-acceptance/<ID>.md`) mevcut ve Story’den link verilmiştir.  
5. Tüm dokümanlar ilgili MP-* formatına uygundur.

---

## 6. Çalışma Akışı (Zorunlu Sıra)

### 6.1 Hazırlık
Bu dosya (AGENT-CODEX) + governance + mimari bağlamı topla:

- Mimari özet:
  - `docs/01-architecture/01-system/01-backend-architecture.md`
  - `docs/01-architecture/01-system/02-frontend-architecture.md`
- Akış ve Story/Task durumu:
  - `docs/05-governance/PROJECT_FLOW.md` (kanonik pano)
- Talep inbox’ı:
  - `docs/05-governance/FEATURE_REQUESTS.md`

### 6.2 Görev Seçimi
Kullanıcı açık bir Story/Epic ID vermediyse varsayılan algoritma:

- `PROJECT_FLOW.md` tablosunda **Son Durum ≠ ✔ Tamamlandı** olan Story’ler arasından,  
- İlgili Epic’i aktif olanlar içinden,  
- `Story Priority` alanı en yüksek olan Story seçilir. **Yüksek sayı = yüksek öncelik** (örn. 900 > 100).  
- Güvenlik etiketi taşıyan (`SEC-*` ID’li veya `security-critical` notlu) Story’ler bütün diğer işlerin önüne çekilir; `Story Priority` yalnız bu kapalı grup içinde seçim yapmak için kullanılır.

### 6.3 Dokümantasyon
Eksik ADR/SPEC/Acceptance varsa önce bunlar ilgili MP formatlarında tamamlanır.  
Story, bu zincir tam olmadan “In Progress” yapılamaz.

#### 6.3.1 Story Çalışma Döngüsü (Epic + ADR + SPEC + Acceptance)
Bu adımlar **zorunludur**; herhangi bir Story üzerinde “In Progress” çalışması başlatılmadan önce zincir eksiksiz okunur ve her adım raporlanır. Şartlar sağlanmıyorsa Story üzerinde kod yazılmaz.

- **Epic:** İş ihtiyacını ve “NEDEN bu işi yapıyoruz?” sorusunu yanıtlar.  
- **ADR:** Mimari/teknik kararları ve sınırları tanımlar; bunlar normatif kurallardır.  
- **SPEC:** Teknik çözüm tasarımını ve akışları anlatır; “NASIL çözeceğiz?” sorusunu yanıtlar.  
- **Acceptance:** Story’nin icra sözleşmesidir; kapsamı ve kabul kriterlerini belirler.

Bu zinciri okuduktan sonra her Story’de aşağıdaki 6 adım bilinçli olarak uygulanır:

1. **NEDEN? – Problem ve amaç**  
   - Problem ve hedef kullanıcı Epic + Story’den okunur.  
   - “Bu Story yapılmazsa hangi risk devam eder?” sorusu yanıtlanmadan Story “In Progress” olmaz.
2. **NE? – Kapsam ve kapsam dışı**  
   - Acceptance maddeleri kapsamın tek gerçeğidir; yazılmamış davranış varsayılan olarak kapsam dışıdır.  
   - Önemli kapsam dışı noktalar gerekiyorsa Story/Acceptance’a işlenir.
3. **NERELERE DEĞECEK? – Etki analizi**  
   - Etkilenecek servis/modül/API/DB değişiklikleri SPEC + ADR üzerinden kontrol edilir.  
   - Güvenlik/audience/permission kararları ADR’den sapmadan uygulanır; kontrat veya veri modeli değişikliği gerekiyorsa önce ilgili ADR/SPEC güncellenir.
4. **NASIL? – Çözüm tasarımı (DTO → Servis → Controller)**  
   - Çözüm sırası DTO/schema → servis/domain → controller olarak ilerler; iş kuralı controller’a gömülmez.  
   - Çalışma küçük, izlenebilir dilimlere bölünür ve her dilim bu sıraya uyar.
5. **NE KIRABİLİR? – Risk & edge-case**  
   - 401/403 ayrımı, boş veri, yanlış parametre, büyük veri setleri, performans ve backward compatibility değerlendirilir.  
   - Tespit edilen riskler raporlama bölümünde madde madde yazılır.
6. **NASIL DOĞRULAYACAĞIM? – Test & doğrulama**  
   - Unit/integration testlerde en az bir 200 ve en az bir 4xx senaryosu bulunur.  
   - Acceptance maddeleri tek tek gözden geçirilir; sağlanan/kısmen sağlanan/ertelenen maddeler gerekçeleriyle raporlanır.  
   - E2E (Playwright/Cypress) veya manuel doğrulama adımları 2–3 maddelik notla belgelenir.  
   - Bu adımların her biri Story raporunda referans gösterilmek zorundadır; eksik adım bulunması Story’nin kapatılmasını engeller.

### 6.4 Planlama
Tahmini eforu **8 saatten büyük** işlerde plan aracı ile en az 2 adım paylaşılır.

- **Plan aracı:** Jira, Linear, GitHub Projects veya eşdeğer.  
- `PROJECT_FLOW.md`, bu araçtaki durumun kanonik yansımasıdır (tek gerçek pano).

### 6.5 Kodlama Sırası
DTO/schema → Servis → Controller

- Değişiklikler **küçük dilimler** halinde yapılır.  
- Mümkün olduğunda `apply_patch` tarzı lokal, izlenebilir değişiklikler tercih edilir.

### 6.6 Test & Doğrulama
- İlgili otomatik testler, lint veya scriptler çalıştırılır.  
- Çalıştırılamıyorsa sebebi ve yerine uygulanacak **manuel doğrulama adımları** raporda yazılır.

### 6.7 Raporlama
- Değişiklikler **dosya:satır** referanslı ve Türkçe özetlenir.  
- Varsayımlar ve belirgin riskler kısaca listelenir.  
- Gerektiğinde 11. maddedeki **Karar & Öneri Çerçevesi** ile değerlendirme eklenir.

### 6.8 Session Log
- Her anlamlı iş bitiminde `docs/00-handbook/session-log.md` tablosuna **yerel saatle** satır eklenir (yoksa iş tamamlanmış sayılmaz).  
- **Kural:** Her satır tek bir Story/Task için yapılan anlamlı işi temsil eder.  
- Bir oturumda birden fazla Story/Task’e dokunulursa her biri için ayrı satır yazılır.  
- Saat alanı ajan makinesinin yerel zamanından alınır (örn. `date '+%Y-%m-%d %H:%M'` veya `new Date().toLocaleString('tr-TR')`).

### 6.9 Temizlik
- Geçici dosyalar/artifaktlar silinir.  
- Geçici log ve deneme script’leri repoda bırakılmaz.

---

## 7. Repo Haritası ve Sınırlar
- Backend repo kökü: bu repo (`.`); varsayılan arama alanı `backend/` + `docs/`.  
- Frontend kodu: ayrı repo (`../frontend`); Codex burada yalnız **okuma** yapar, FE build/config artefaktları backend’e taşınmaz.  
- Dizin yapıları için kanonik kaynaklar:
  - Backend: `docs/00-handbook/BACKEND-PROJECT-LAYOUT.md`
  - Frontend: `docs/00-handbook/FRONTEND-PROJECT-LAYOUT.md`
- Codex repo keşfederken bu iki dokümanı ve bu dosyayı (AGENT-CODEX) referans alır; rastgele klasör taramaz.

---

## 8. Guardrail’ler (Katı Yasaklar)
- Gizli anahtar, `.env*`, Kubernetes secret paylaşılmaz.  
- `git reset --hard`, `rm -rf /`, sistem konfigürasyon değişiklikleri gibi destrüktif komutlar yasaktır.  
- Frontend build artefaktı (dist, build, bundler config, MFE uygulaması) backend repo’ya konmaz.  
- Mimari/roadmap dokümanlarında değişiklik için iki aşamalı (taslak + uygulama) onay zorunludur.  
- Kopya dosya (`*-copy`, `copy_of` vb.) oluşturulmaz.  
- PII ve müşteri verisi maskelenmeden paylaşılmaz.  
- **Yerel kaynak koruması:** Git işlemlerinde yerel working copy veya yedekler silinmez; snapshot/deneme değişiklikleri her zaman kopya üzerinde yapılır.  
- **Kod stili:** Yalnızca gerekli açıklamalar eklenir; otomatik formatlayıcılar (prettier, `eslint --fix` vb.) kullanıcı onayı olmadan tüm repo üzerinde koşturulmaz.

---

## 9. Git / Versiyon Kontrol Modu

Codex hem Git kullanılan hem de Git hiç devrede olmayan ortamlarda çalışabilir.  
Versiyon kontrol modu, kullanıcının prompt’undaki bilgiye göre belirlenir.

### 9.1 Git’li Çalışma

- Kullanıcı açıkça “Bu repo Git ile yönetiliyor” diyorsa veya `git` komutları kullanılabiliyorsa:
  - `branch`, `commit`, `diff` gibi kavramlar geçerlidir.
  - Değişiklikler mümkün olduğunca küçük ve anlamlı dilimler hâlinde önerilir.
  - **Yerel kaynak koruması** devam eder:
    - `git reset --hard`, `git clean -fdx` gibi destrüktif komutlar önerilmez.
    - Snapshot almak / yeni branch açmak tercih edilir.

### 9.2 Git Olmadan Çalışma

- Kullanıcı “Git kullanmıyorum” diyorsa veya ortamda Git yoksa:
  - Codex versiyon kontrol sistemi olmadığını varsayar.
  - Kullanıcı bir **proje kök klasörü** verir, örn.:
    - `PROJE_KOKU: /Users/halilkocoglu/Documents/dev/backend`
  - Bu klasör o oturum için **kanonik çalışma alanıdır**.

Bu modda kurallar:

- **9.2.1 Kapsam Sınırı**
  - Codex yalnızca `PROJE_KOKU` ve altındaki dosyalar üzerinde değişiklik yapar.
  - Sistem klasörlerine veya proje kökü dışına çıkmaz.
  - Geçici build/dist çıktıları gerekiyorsa, onlar da `PROJE_KOKU` altında tutulur.

- **9.2.2 Git Komutu YOK**
  - `git status`, `git diff`, `git commit`, `git reset` vb. komutlar kullanılmaz veya önerilmez.
  - Branch/snapshot dili yerine **dosya tabanlı değişiklik** dili kullanılır:
    - “`X` dosyasının `Y` satırlarında şu değişiklikleri yap” şeklinde.

- **9.2.3 Değişiklik Stratejisi**
  - Mevcut dosyaları **yeniden yazmak** yerine mümkün olduğunca minimal patch tercih edilir.
  - Yeni dosya gerekiyorsa:
    - `NAMING.md`, `FRONTEND-PROJECT-LAYOUT.md`, `BACKEND-PROJECT-LAYOUT.md` kurallarına uyan isim ve konum kullanılır.
  - Var olan dokümanların path’leri değişmez; rename/taşıma ancak kullanıcı açıkça isterse yapılır.

- **9.2.4 Raporlama**
  - Her görev sonunda Codex yapılan işlemleri dosya bazında raporlar:
    - Dosya yolu (relatif)
    - Ne değişti? (kısa açıklama)
    - Hangi Story/Acceptance maddesine hizmet ediyor?
  - Git olmadığı için “commit”, “branch” gibi ifadeler yerine:
    - “Şu şu dosyalarda şu davranış değişti” dili kullanılır.

- **9.2.5 Güvenlik ve Koruma**
  - Toplu silme, kök dizinde format değişikliği gibi geri döndürülemez işlemlerden kaçınılır.
  - Kullanıcı “deneme” amaçlı bir şey istese bile:
    - Codex önce etkisini kısaca anlatır,
    - Sonra minimal bir alternatif önerir (tek dosya, küçük patch, vb.).

---

## 10. Küçük Değişiklik İstisnası
Efor ≤ **60 dk** ve sadece aşağıdaki kapsamdaysa yeni ADR/SPEC/Story **zorunlu değildir**:

- Typo düzeltmeleri  
- Metin/etiket değişiklikleri  
- Kozmetik CSS/UI spacing düzenlemeleri  

**İstisna dışı durumlar (her zaman tam zincir uygulanır):**

- İşlevsel davranışı etkileyen değişiklikler  
- API kontratını değiştiren her şey  
- Veri modelini değiştiren her şey  

Bu hallerde 4. ve 5. maddelerdeki zincir aynen uygulanır.

---

## 11. Doküman Politikası
- **Only-needed** ve **tıklanabilir link** kuralı esastır.  
- Listeler mümkünse **maksimum 5 madde** içerir.  
- Meta bilgisi zorunludur: `title/owner/status/last_review` (en az “Son güncelleme”).  
- 60 gün dokunulmayan referanslar gözden geçirilir; gerekirse arşivlenir.  

**PR / Değişiklik Checklist’i (doc-impact standardı):**

- API davranışı değişti → İlgili `docs/03-delivery/api/*.md` güncellenir.  
- Güvenlik etkisi varsa → `docs/01-architecture/04-security/**/*` ve `docs/agents/**/*` güncellenir.  
- Kullanım/akış etkisi varsa → İlgili Story/Acceptance ve `PROJECT_FLOW.md` notları güncellenir.  
- Infra/config/secret/route değiştiyse → `BACKEND-ARCH-STATUS` ve `FRONTEND-ARCH-STATUS` dokümanları ile `session-log.md` güncellenir; ilgili Story/Task için PROJECT_FLOW notu yazılır.  
- Hiçbir doküman etkisi yoksa → Değişiklikte `no-doc-impact` etiketi kullanılır.

---

## 12. Karar ve Öneri Çerçevesi (Ajanın İç Değerlendirme Matrisi)

Codex, herhangi bir konuda “hangisi daha doğru / daha uygun?” türü teknik/stratejik öneri üretirken, **kullanıcıya gereksiz soru sormadan**, mümkün olduğunca mevcut doküman ve kodu okuyarak aşağıdaki çerçeveyi uygular.

Öncelik sırası:

- Önce: AGENT-CODEX + mimari dokümanlar + ilgili SPEC + ADR + Acceptance + PROJECT_FLOW okunur.
- Sonra: Seçenekler bu çerçeveye göre tartılır.
- Eksik bilgi varsa kullanıcıdan gereksinim listesi istemek yerine makul bir varsayım yapılır ve yanıtta açıkça **“Varsayım: …”** şeklinde belirtilir.

Değerlendirme en az şu 11 başlığa göre yapılır:

1. **İş ihtiyacı ve değer**
   - Çözüm, ilgili Epic/Story’nin tarif ettiği problemi gerçekten adresliyor mu?
   - Hangi kullanıcı/rol için ne değer üretiyor, “sırf teknik güzelleştirme” mi yoksa somut fayda mı?

2. **Kullanıcı deneyimi**
   - Akışı sadeleştiriyor mu, yoksa kullanıcı açısından gereksiz friksiyon mu ekliyor?
   - Hata durumları, login/logout davranışları ve uyarılar tutarlı mı?

3. **Mevcut sistem ve mimariyle uyum**
   - Var olan mimari, pattern’ler, naming, güvenlik modeli ve dokümanlarla çelişiyor mu?
   - “Mevcut rayların üzerinde mi gidiyor, yoksa ray döşemeden tren mi sürüyor?” sorusuna bakılır.

4. **İzlenebilirlik (log/metric/trace/audit)**
   - Log/metric/trace/audit gereksinimleriyle uyumlu mu, gözlemlenebilir mi?
   - errorCode, traceId, event isimleri mevcut standarda uyuyor mu; incident anında debug etmek kolay mı?

5. **Performans ve ölçeklenebilirlik**
   - Latency, throughput, büyük liste/rapor, concurrency senaryolarında kabul edilebilir mi?
   - Gereksiz network çağrısı, ağır join, bloklayan IO, gereksiz round-trip veya N+1 üretiyor mu?

6. **Güvenlik ve uyum**
   - OAuth2/JWT, audience/scope, permission/profil, 401/403 ayrımı, CORS ve rate limiting açısından risk getiriyor mu yoksa korumayı güçlendiriyor mu?
   - AGENT-SECURITY ve OWASP gibi rehberlerle uyumlu mu?

7. **Bakım ve teknik borç**
   - Kodu/konfigi daha okunur ve modüler hale getiriyor mu, yoksa yeni bir “hack” katmanı mı ekliyor?
   - Yeni gelen bir geliştiricinin bunu anlaması ne kadar zor; ileride refactor’ı kolay mı?

8. **Operasyon ve işletim**
   - Runbook, deployment, rollback, incident response süreçlerini zorlaştırıyor mu kolaylaştırıyor mu?
   - Health/readiness, konfigürasyon yönetimi ve alerting açısından beklenmedik yük yaratıyor mu?

9. **İteratif geliştirme ve geri alınabilirlik**
   - Çözüm küçük adımlara (MVP → genişletme) bölünebiliyor mu?
   - Feature flag / config ile kontrollü açılıp kapatılabilir mi ve yanlış çıkarsa geri almak kolay mı?

10. **Sektör standartları ve en iyi uygulamalar**
    - HTTP status code kullanımı, REST/JSON pratikleri, 12-Factor, genel endüstri pattern’leri ile uyumlu mu?
    - “Bu problemi dünyada nasıl çözüyorlar?” sorusuna göre makul bir tercihi mi temsil ediyor?

11. **Kaynak tüketimi ve maliyet (CPU/RAM/disk/network)**
    - CPU, RAM, disk ve network üzerinde gereksiz yük yaratıyor mu?
    - Özellikle rapor, streaming, büyük dataset, SSE/WebSocket gibi senaryolarda uzun vadeli maliyetleri artırıyor mu?

---

### 12.1 Karar Üretirken Bilgi Toplama Prensibi

- Codex, bu değerlendirmeyi yaparken **önce** mevcut doküman ve repo içeriğini tarar:
  - İlgili Epic → Story → SPEC → ADR → Acceptance zincirini okur.
  - Gereksinim maddelerini, hata senaryolarını, güvenlik sınırlarını ve iş önceliğini buradan çıkarır.
- Kullanıcıdan tekrar “gereksinim listesini tek tek söyle” demek yerine:
  - Dokümanlardan ve koddan anladığı kadarıyla en iyi uygulamaya göre bir çözüm önerir.
  - Eksik/şüpheli gördüğü noktalarda:
    - Varsa: “Acceptance’da X net değil, şu yorumu varsayıyorum: …”
    - Yoksa: “Varsayım: duplicate email için 409, auth hatası için 401, business constraint için 403 kullanacağız.” gibi açık varsayımlar yazar.
- Kullanıcı yeni bir tercih belirtirse (örneğin “bu durumda 409 değil 422 kullanmak istiyoruz”), Codex sonraki kararlarında bu tercihi dikkate alır.

Bu çerçeve, her “hangisini seçelim / ne yapalım?” sorusunda Codex’in otomatik olarak kullanacağı **genel karar lensidir**. Kullanıcıya gereksiz soru sormadan, mevcut doküman ve mimariyi baz alarak öneri üretmek zorunludur.
---

## 13. İletişim, Onay ve Sorumluluk
- Varsayılan dil: Türkçe.  
- Her yanıt: kısa durum özeti + sonraki adımlar içerir.  
- Komut logu sadece istendiğinde paylaşılır; normalde özet verilir.  
- Nihai karar her zaman Halil K.’ya aittir.  
- Onay alınmadan yapılan iş → **Sorumlu: Codex**  
- Onay sonrası yapılan iş → **Sorumlu: Halil K.**  
- Mimari/roadmap gibi iki aşamalı onay gerektiren işlerde ajan “Onay bekleniyor” mesajıyla durur ve yanıtlarını onay sonrasına erteler; onay gelmeden ilerlemez.  
- Bu dosya sadece Halil K. tarafından güncellenebilir; Codex yeni sürümü okuyup eski alışkanlıkları terk eder.

**Bu dosya Single Source of Truth’tur.**  

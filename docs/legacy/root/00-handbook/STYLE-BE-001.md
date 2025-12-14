---
title: "STYLE-BE-001 – Backend Kod Yazım Rehberi"
status: published
owner: "@team/backend"
last_review: 2025-11-18
tags: ["style", "backend", "quality"]
---

# STYLE-BE-001  
**Başlık:** Backend Kod Yazım Rehberi  
**Versiyon:** v1.0  
**Tarih:** 2025-11-18  
**Kapsam:** ERP Backend Servisleri (Java / Spring Boot, Node.js, Python vb.)  

Bu doküman, backend geliştirmelerinde **okunabilir**, **sürdürülebilir**,  
**güvenli** ve **standart** bir kod kalitesi sağlamak için uyulması gereken kuralları tanımlar.  
Tüm backend servisleri bu kurallara uymakla yükümlüdür.

> Bu doküman backend kod stili için **kanonik kaynaktır**; tüm özetler ve referanslar buraya işaret eder.
> Mimari ve endpoint sözleşmesi için bkz.  
> - Backend mimarisi ve parametre sözleşmesi: `docs/01-architecture/01-system/01-backend-architecture.md`  
> İsimlendirme için bkz.  
> - [NAMING.md](./NAMING.md)

---

## 1. Temel İlkeler (Core Principles)

- Kod açık, basit ve anlaşılır olmalıdır (**Clean Code**).  
- Her fonksiyon **tek sorumluluk** üstlenmelidir (SRP).  
- Gereksiz yorum satırı kullanılmaz; kod kendini açıklamalıdır.  
- Magic number kullanılmaz → sabitler (`const`, `static final`) tanımlanır.  
- Kod tekrarına izin verilmez (DRY).  
- Her anlamlı geliştirme mutlaka test ile desteklenir.  
- Karmaşıklık mümkün olduğunca azaltılır (KISS).  
- Performans ve güvenlik etkisi olan kararlar ADR ile kayıt altına alınır.  

---

## 2. Mimari Kurallar (Architecture Rules)

### 2.1 Katman Yapısı (Layered Architecture)

Her backend servisi aşağıdaki standart yapıya göre düzenlenir (dil/framework’e göre isimler uyarlanabilir):

```text
src/
  controller/   → HTTP endpoint’ler, request/response
  service/      → İş mantığı (business logic)
  repository/   → Veri erişimi (DB sorguları, ORM)
  entity/       → Veri model sınıfları
  dto/          → Girdi/çıktı veri modelleri
  config/       → Yapılandırma
  utils/        → Yardımcı fonksiyonlar
  middleware/   → Filter, interceptor vb. (opsiyonel)
```

**Kurallar**

- Business logic **controller’da yer alamaz**.  
- Akış: `Controller → DTO → Service → Repository`; bu yön tersine çevrilmez.  
- API validasyonları DTO seviyesinde (Bean Validation / schema) yapılır.  
- Gelişmiş filtre/sort/search sözleşmesi `docs/01-architecture/01-system/01-backend-architecture.md` ile uyumlu olmalıdır.  

---

## 3. İsimlendirme Kuralları (Naming Conventions)

İsimlendirme için ana referans: [NAMING.md](./NAMING.md). Özet:

### 3.1 Değişken ve Fonksiyonlar

- `camelCase` → `userId`, `calculateTotal()`  
- Kısa ama **anlamlı** isim zorunlu:  
  - `data1`, `tmp`, `test2` → yasak  

### 3.2 Sınıflar ve Dosyalar

- `PascalCase` → `UserService`, `UserController`  
- Repository → `UserRepository`  
- DTO → `LoginRequestDto`, `UserResponseDto`  

### 3.3 Endpoint İsimleri

REST kurallarına sıkı uyulur:

```text
GET    /api/users
GET    /api/users/{id}
POST   /api/users
PUT    /api/users/{id}
DELETE /api/users/{id}
```

**Yasak örnekler:**

- `/getUser`, `/updateUser`, `/deleteUser`
- Fiil içeren path isimleri (`/doLogin`, `/changePasswordNow` vb.)

---

## 4. Logging Standartları

### 4.1 Format ve Seviyeler

- Log’lar üretim ortamında makine tarafından okunabilir formatta (JSON) tutulur veya log toplama katmanında JSON’a dönüştürülür.  
- `DEBUG` sadece lokal/dev ortamda; prod’da default seviye `INFO` veya `WARN`.  
- Kullanıcıya **stack trace** gösterilmez.

Örnek uygulama log’u:

```json
{
  "traceId": "7ab9123",
  "level": "INFO",
  "event": "login_success",
  "userId": "948310aa",
  "path": "/api/auth/login"
}
```

### 4.2 Gizli Bilgiler

Aşağıdakiler asla loglanmaz:

- Şifre (ham veya hash)  
- Token (JWT access/refresh)  
- TCKN, vergi numarası vb. kimlik numaraları  
- Kredi kartı bilgisi  
- Her türlü sır niteliğindeki veri  

### 4.3 TraceID

- Her HTTP request’e unique `traceId` eklenmelidir (gateway veya servis tarafından).  
- Log satırlarında `traceId` zorunludur; dağıtık trace (OTEL) ile uyumlu olacak şekilde taşınır.

---

## 5. Hata Yönetimi (Error Handling)

- Tüm hatalar standart bir error response ile dönmelidir (dil/framework’e göre JSON şeması değişebilir ama anlam aynı kalır):

```json
{
  "error": "INVALID_CREDENTIALS",
  "message": "E-posta veya şifre hatalı.",
  "fieldErrors": []
}
```

- Validation hataları alan bazlı olmalıdır (`fieldErrors` listesi).  
- Global Exception Handler zorunludur (Spring `@ControllerAdvice`, Node middleware, vb.).  
- Global handler, `STYLE-API-001` içindeki `ErrorResponse` şemasını (error/message/fieldErrors/meta.traceId) döndürür ve traceId üretir/taşır.  
- Controller içinde geniş `try/catch` blokları kullanılmaz; business/service katmanında özel istisnalar fırlatılır, global handler’da map edilir.  
- advancedFilter gibi parse/adaptor hataları için tutarlı hata kodları kullanılır (`invalid_advanced_filter_*`).  

---

## 6. Güvenlik (Security)

### 6.1 Veri Güvenliği

- Şifreler **hash’lenmiş** saklanır (bcrypt, Argon2 vb.).  
- Token’lar plaintext olarak asla loglanmaz veya DB’de tutulmaz.  
- Hassas alanlar maskelenir (`****1234` gibi).

### 6.2 API Güvenliği

- Tüm korumalı endpoint’ler JWT / OAuth2 / Session mekanizmasından biriyle korunur.  
- Authorization (yetkilendirme) middleware/interceptor veya service katmanında yapılır; controller içerisinde dağınık role check yapılmaz.  
- Yetki kontrolü yapılırken audit/log kaydı alınır (özellikle export, admin işlemleri).  

### 6.3 Input Güvenliği

- SQL injection → ORM, prepared statements, whitelist.  
- XSS → input sanitization + output encoding (özellikle template/render tarafında).  
- Path traversal → dosya erişimlerinde whitelist + sanitize edilmiş path’ler.  

---

## 7. Performans Kuralları

- Gereksiz database çağrısı yapılmaz; aynı request içinde bir kere okunabilecek veri tekrar okunmaz.  
- Büyük veri listeleri için **pagination zorunludur** (`page/pageSize`).  
- İndeks ihtiyacı varsa ADR ile belgelenir ve migration ile eklenir.  
- Döngülerde pahalı hesaplama yapılmaz, gerekiyorsa precompute/cache uygulanır.  
- N+1 query problemi yasaktır (fetch join, batch fetch, projection kullanılır).  
- `search / advancedFilter / sort` parametreleri için uygun indeksleme yapılır (bkz. `docs/01-architecture/01-system/01-backend-architecture.md`).  

---

## 8. Test Standartları

### 8.1 Türler

- Unit test  
- Integration test (DB, HTTP, security)  
- Smoke / env test (örn. `scripts/test-users-and-variants.sh`)  

### 8.2 Kurallar

- Testler bağımsız ve idempotent olmalıdır.  
- Mock yerine mümkünse lightweight stub/fake tercih edilir.  
- Test isimleri anlamlı olmalıdır:

```ts
it('should return token when credentials are valid', () => {
  // ...
});
```

- advancedFilter / CSV export / security guardrail gibi kritik davranışlar için hem unit hem integration test bulunur.  

---

## 9. Bağımlılık Yönetimi (Dependencies)

- Yeni önemli bağımlılık eklenmeden önce ADR açılır veya mevcut ADR güncellenir.  
- Kullanılmayan paketler periyodik olarak temizlenir.  
- Versiyonlar kilitlenir (`pom.xml`, `package-lock.json`); prod/test/stage arasında drift olmaması esastır.  

---

## 10. Kod İnceleme (Code Review) Kuralları

PR review öncesi kontrol listesi:

- SPEC’e uyum sağlanmış mı?  
- ACCEPTANCE dokümanı güncel mi / geçiyor mu?  
- İlgili ADR kararları uygulanmış mı?  
- Bu rehber (STYLE-BE-001) ve [NAMING.md](./NAMING.md)’e uygun mu?  
- Testler var mı ve CI’de geçiyor mu?  
- Loglama doğru seviyede mi, hassas veri sızmıyor mu?  
- Belirgin performans / güvenlik sorunu var mı?  

Review onayı olmadan `main`/`master` branşına merge yasaktır.

---

## 11. Commit Mesajı Formatı

```text
TYPE(SCOPE): Kısa açıklama
```

Örnekler:

```text
feat(auth): login endpoint eklendi
fix(user): null lastLogin hatası düzeltildi
refactor(order): hesaplama optimize edildi
```

Türler:

- `feat`  
- `fix`  
- `refactor`  
- `test`  
- `docs`  
- `perf`  
- `chore`  

---

## 12. Dosya Yapısı Örneği

```text
src/
  controller/
  service/
  repository/
  entity/
  dto/
  config/
  utils/
  middleware/
```

Bu yapı dil/framework’e göre uyarlanabilir; ancak controller → service → repository akışı ve sorumluluk ayrımı korunmalıdır.

---

## 13. Versiyonlama

- Kod: Git tag (release versiyonları).  
- Spec: `version` alanı ile takip edilir.  
- ADR: Dosya isimlerinde tarih/id ve içerikte versiyon/kapsam bilgisi yer alır.  

---

## 14. Definition of Quality (Tamamlanma Kriteri)

Bir backend geliştirme DONE sayılabilmesi için:

- İlgili SPEC/ACCEPTANCE dokümanına uyumlu olması,  
- Acceptance testlerinin başarılı olması,  
- Gerekliyse ADR oluşturulmuş/güncellenmiş olması,  
- Bu rehbere (STYLE-BE-001) ve `docs/01-architecture/01-system/01-backend-architecture.md` içindeki sözleşmelere uygun olması,  
- Code review onayı almış olması,  
- Testlerin CI ortamında çalışıyor olması,  
- Loglama ve güvenlik kontrollerinin doğru uygulanmış olması,  
- `PROJECT_FLOW.md` üzerinde ilgili Story/Epic satırının güncellenmiş olması gerekir.

---

Bu doküman, backend için **tek resmi kod kalitesi standardıdır**.  
Yeni servisler ve refactor çalışmaları bu rehbere göre tasarlanmalı ve gözden geçirilmelidir.

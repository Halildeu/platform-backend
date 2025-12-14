---
title: "STYLE-API-001 – API Tasarım ve Yazım Rehberi"
status: published
owner: "@team/platform-arch"
last_review: 2025-11-19
tags: ["style", "api", "contract"]
---

# STYLE-API-001  
**Başlık:** API Tasarım ve Yazım Rehberi  
**Versiyon:** v1.0  
**Tarih:** 2025-11-18  
**Kapsam:** ERP Backend → Frontend → Microservice API sözleşmesi

> Bu doküman API tasarım stili için **kanonik kaynaktır**; tüm özetler ve referanslar buraya işaret eder.
> İlgili dokümanlar:  
> - Backend kod kalitesi: `docs/00-handbook/STYLE-BE-001.md`  
> - Frontend kod kalitesi: `docs/00-handbook/STYLE-FE-001.md`  
> - Örnek API sözleşmeleri: `docs/03-delivery/api/users.api.md`, `docs/03-delivery/api/auth.api.md`

---

## 1. Amaç

Tüm backend servisleri tarafından expose edilen API'lerin:

- tutarlı,  
- sürdürülebilir,  
- güvenli,  
- test edilebilir,  
- backward-compatible  

olacak şekilde tasarlanması için standart belirler.

Bu doküman **API’nin resmi sözleşme standardıdır**.  
Tüm FE/BE ekipleri bu kurallara uymalıdır.

---

## 2. Genel API İlkeleri

- **RESTful** tasarım esastır.  
- Kaynak temelli path yapısı kullanılır (`/api/users`, `/api/projects`).  
- HTTP metodları semantiğe uygun kullanılır (GET/POST/PUT/DELETE).  
- URL içinde fiil (`/getUser`, `/doLogin`) kullanılmaz.  
- API yüzeyi mümkün olduğunca tahmin edilebilir ve standart bir yapı sunar.

---

## 3. Endpoint Tasarım Kuralları

### 3.1 CRUD Standartları

```text
GET    /api/users
GET    /api/users/{id}
POST   /api/users
PUT    /api/users/{id}
DELETE /api/users/{id}
```

### 3.2 Yasak Desenler

- `/getUsers`  
- `/updatePassword`  
- `/doLogin`  
- `/deleteUser`

### 3.3 Alt Kaynak Tasarımı

```text
/api/orders/{id}/items
/api/projects/{id}/tasks
/api/users/{id}/roles
```

---

## 4. Query Parametre Sözleşmesi

Listeleme endpoint’lerinde ortak parametre seti:

- `page` (number, 1..N)  
- `pageSize` (number, 1..1000)  
- `sort` (string)  
- `search` (string, opsiyonel)  
- `advancedFilter` (string, opsiyonel)

Örnek (`users.api.md` ile uyumlu):

```text
GET /api/users/all?page=1&pageSize=50&search=john&advancedFilter=%7B...%7D&sort=fullName,asc;email,desc
```

### 4.1 Pagination

- Varsayılan: `page` 1‑bazlı sayfa numarasıdır (1..N).  
- `pageSize` → sayfa başına kayıt sayısı.  
- Legacy uçlar (örn. `audit-events`) response içinde 0‑bazlı `page` alanı dönebilir; bu durum ilgili `*.api.md` dokümanında açıkça belirtilmelidir.

### 4.2 Sorting

`sort` paramı çoklu sütun sıralamayı destekler:

```text
sort=fullName,asc;email,desc
```

Backend tarafında whitelist dışı alan/yonler ya 400 döndürür ya da yok sayılır; karar API dokümanında belirtilmelidir (`users.api.md` gibi).

### 4.3 Advanced Filter

- `advancedFilter` URL‑encoded JSON’dur.  
- Şema ve whitelist için **tek SSOT** bu doküman (`STYLE-API-001`) ve ilgili `*.api.md` dosyalarıdır; backend architecture dosyaları yalnızca özet/davranış notu taşır.  
- Örnek form:

```json
{
  "logic": "and",
  "conditions": [
    { "field": "name", "op": "contains", "value": "john" },
    { "field": "sessionTimeoutMinutes", "op": "inRange", "value": 15, "value2": 60 }
  ]
}
```

Bozuk JSON veya whitelist dışı alan/operatörler → 400 (`invalid_advanced_filter_*`) olarak döndürülmelidir (bkz. `docs/01-architecture/01-system/01-backend-architecture.md` sözleşme notları).

---

## 5. Response Formatı

### 5.1 Listeleme Yanıtı (Paginated)

`users.api.md` sözleşmesi gibi liste endpoint’leri varsayılan olarak aşağıdaki şemayı kullanır:

```json
{
  "items": [
    {
      "id": "1",
      "fullName": "John Doe",
      "email": "john@example.com",
      "role": "USER",
      "status": "ACTIVE"
    }
  ],
  "total": 12345,
  "page": 1,
  "pageSize": 50
}
```

- `items`: Sonuç listesi (domain gereği isim değişebilir; ör. `audit-events.api.md` için `events`)  
- `total`: Toplam kayıt sayısı  
- `page`: Mevcut sayfa (1‑bazlı)  
- `pageSize`: Sayfa başına kayıt sayısı  

### 5.2 Tek Kayıt Yanıtı

Tekil kaynaklar için sade JSON nesnesi dönebilir:

```json
{
  "id": 1,
  "name": "John Doe",
  "email": "john@example.com",
  "role": "ADMIN",
  "enabled": true
}
```

Mutasyonlarda ek olarak `auditId` vb. alanlar eklenebilir (`users.api.md`’deki `auditId` örneği gibi).

---

## 6. Hata Yapısı (Error Response)

Tüm servislerde hata yapısı mantıksal olarak tutarlı olmalıdır; resmi şema **`ErrorResponse`**:

```json
{
  "error": "INVALID_CREDENTIALS",
  "message": "E-posta veya şifre hatalı.",
  "fieldErrors": [
    { "field": "email", "message": "Geçerli bir e-posta adresi giriniz." }
  ],
  "meta": {
    "traceId": "7ab9123",
    "timestamp": 1713342323
  }
}
```

- `error`: Makine tarafından okunabilir hata kodu.  
- `message`: Kullanıcıya gösterilebilecek özet mesaj.  
- `fieldErrors`: Alan bazlı validation hataları (opsiyonel).  
- `meta.traceId`: Log/trace ile korelasyon için zorunlu.  

Global handler’lar ve client’lar bu `ErrorResponse` şemasını kullanır; backend tarafında aynı isimle döndürülmelidir.

### 6.1 HTTP Kod Sözleşmesi

| Kod | Anlam                     |
| --- | ------------------------- |
| 200 | Başarılı                  |
| 201 | Kaynak oluşturuldu        |
| 204 | İçerik yok                |
| 400 | Validation / format hatası|
| 401 | Yetkisiz                  |
| 403 | Yetki yok                 |
| 404 | Bulunamadı                |
| 409 | Çakışma                   |
| 422 | İş kuralı hatası          |
| 429 | Çok fazla istek (rate-limit) |
| 500 | Sunucu hatası             |

Her `*.api.md` dokümanında bu tabloda kullanılan kodlar için somut örnekler verilmelidir.

---

## 7. Güvenlik Standartları

- Tüm korumalı endpoint’lerde JWT / OAuth2 veya internal API key doğrulaması yapılmalıdır.  
- Token doğrulama hem gateway hem backend servis katmanında uygulanır (bkz. security dokümanları).  
- Hassas bilgiler (şifre, token, TCKN, kredi kartı vb.) response’larda veya hata mesajlarında gösterilmez.  
- Rate‑limit politikaları (ör. CSV export) ilgili `*.api.md` ve runbook dokümanlarında belirtilmelidir.

---

## 8. Versiyonlama (API Versioning)

Versiyonlama gerektiğinde path ile verilir:

```text
/api/v1/users
/api/v2/users
```

Kurallar:

- `v1` sabitlenir → breaking change yapılamaz.  
- Breaking change gereken durumlarda → `v2` açılır.  
- Eski versiyonlar önce `deprecated`, sonra planlı olarak kaldırılır; takvim ilgili API dokümanında belirtilir.

### 8.1 v1 Standardizasyonu

- `QLTY-API-V1-STANDARDIZATION-01` Story’si kapsamında `/api/v1/**` path’i tüm servisler için zorunlu kanaldır; FE service katmanlarının tamamı aynı path’i tüketir.
- PagedResult zarfı (`items`, `total`, `page`, `pageSize`) ve `sort/search/advancedFilter` parametre sözleşmesi her API için uygulanır; dokümana link verilerek doğrulanır.
- Legacy `/api/...` path’leri yalnızca geçiş sürecinde `@Deprecated` olarak tutulur; kaldırma takvimi ilgili API dosyasında ve `PROJECT_FLOW.md` notunda yer almalıdır.

---

## 9. Observability / Monitoring

Her API çağrısı için:

- `traceId` üretilmeli veya taşınmalı,  
- Çalışma süresi ölçülmeli (timer/metrik),  
- Hata oranı izlenmeli; kritik hatalarda alarm (Slack/email vb.) üretilmelidir,  
- Loglar JSON formatında ve en azından `level`, `event`, `traceId`, `path` alanlarını içermelidir.  

Detaylar için ilgili observability ve runbook dokümanlarına bakılmalıdır.

---

## 10. Backward Compatibility Kuralları

- Var olan JSON alanı kaldırılamaz.  
- Bir alanın tipi değiştirilemez (string → number gibi).  
- Zorunlu alan sonradan zorunlu hale getirilemez.  
- Yeni alan eklenebilir ama zorunlu olmamalıdır (default değeri olmalı).  
- Büyük yapısal değişiklik → yeni major API versiyonu (v2) açılmalıdır.

---

## 11. API Dokümanlarının Yapısı

Her API için `docs/03-delivery/api/<domain>.api.md` dosyası aşağıdaki bölümleri içermelidir:

- Amaç ve kapsam  
- Endpoint listesi (path + method + query/body)  
- Örnek istek/yanıtlar  
- Hata kodları ve hata payload örnekleri  
- Güvenlik (header’lar, scopes)  
- Kabul kriterleri ve ilgili SPEC/ACCEPTANCE/ADR linkleri  

`docs/03-delivery/api/README.md` bu dosyaların indeksini ve örneklerini özetler.

---

## 12. API Quality (DONE Kriteri)

Bir API değişikliği tamamlanmış sayılabilmesi için:

- İlgili SPEC ile uyumlu,  
- Tüm ACCEPTANCE kriterlerini karşılamış,  
- İlgili ADR kararlarını uygulamış,  
- Bu STYLE-API-001 kurallarına uymuş,  
- Unit + integration testleri çalışıyor,  
- Pagination/sort/search/advancedFilter sözleşmesi doğru,  
- Response ve error yapıları `*.api.md` ile uyumlu,  
- `PROJECT_FLOW.md` üzerinde ilgili STORY/Epic “Done” durumuna alınmış olmalıdır.

---

Bu doküman, API tasarımı ve sözleşmeleri için **tek resmi stil standardı**dır.  
Backend, frontend ve QA ekipleri için bağlayıcı kabul edilir; ihtiyaçlar değiştikçe versiyon numarası artırılarak güncellenecektir.

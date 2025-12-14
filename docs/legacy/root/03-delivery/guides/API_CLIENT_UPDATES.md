# API İstemcileri Güncelleme Rehberi

Amaç: İstemcilerin (FE, Postman, 3rd‑party) yeni güvenlik ve başlık sözleşmelerine uyumlu şekilde API çağrılarını güncellemesi.

## 0) Temeller (Base URL, Kimlik, Başlıklar)
- Base URL (Gateway): `http(s)://<gateway-host>/api`
- Kimlik (kullanıcı): Login ile alınan JWT → `Authorization: Bearer <JWT>`
- Kimlik (servis‑servis): client_credentials akışı. Rehber: `docs/01-architecture/04-security/identity/02-client-credentials-jwt.md`
- Zorunlu başlıklar (domain bağlamı):
  - `X-Company-Id` (zorunlu)
  - Opsiyonel: `X-Project-Id`, `X-Warehouse-Id`
- Ortak başlık sözleşmesi: `docs/03-delivery/api/common-headers.md`

## 1) Login (Kullanıcı JWT)
- Endpoint: `POST /api/auth/login`
- Body:
```json
{ "email": "admin@example.com", "password": "admin1234", "companyId": 42 }
```
- Notlar:
  - `companyId` sağlanırsa JWT claim’lerine bağlam/izin yansır (`permissions`).
  - FE, token’ı saklar ve tüm çağrılarda `Authorization` başlığına ekler.

Doğrulama (curl):
```bash
BASE=http://localhost:8080/api
TOK=$(curl -s "$BASE/auth/login" -H 'Content-Type: application/json' \
  -d '{"email":"admin@example.com","password":"admin1234","companyId":42}' | jq -r .token)
echo "$TOK" | wc -c  # > 100 beklenir
```

## 2) Kullanıcı Oluşturma
- Endpoint: `POST /api/users/register`
- Başlıklar: `Authorization: Bearer <JWT>`, `X-Company-Id`
- Body: `name`, `email`, `password`
- Yetki: `MANAGE_USERS` (403: yetki yok)

## 3) Kullanıcı Güncelleme
- Endpoint: `PUT /api/users/{id}`
- Başlıklar: `Authorization`, `X-Company-Id` (+ gerekirse proje/depo)
- Body örn:
```json
{ "name": "Yeni İsim", "role": "ADMIN", "enabled": true }
```
- Yetki: `MANAGE_USERS`

## 4) Korumalı Listeleme
- Endpoint: `GET /api/users/all`
- Başlıklar: `Authorization`, `X-Company-Id`
- İlgili API dokümanı: `docs/03-delivery/api/users.api.md`
- Not: Sıralama/filtreleme kuralları, AdvancedFilter sözleşmesi ile uyumlu olmalı.

## 5) Gizlilik ve Export Guard
- CSV eksportlarında gateway tarafında export guard aktiftir.
- Büyük veri/PII içeren isteklerde politikaya uygun başlık kullanın (örn. `X-PII-Policy: mask`).
- İzleme/uyarılar: `docs/04-operations/01-runbooks/81-export-guard-tuning.md`

## 6) Postman / Environment Önerisi
- Env değişkenleri: `BASE_URL`, `JWT`, `COMPANY_ID`
- Pre‑request örneği (JWT yoksa login):
```javascript
if (!pm.environment.get('JWT')) {
  pm.sendRequest({
    url: pm.environment.get('BASE_URL') + '/auth/login',
    method: 'POST',
    header: { 'Content-Type': 'application/json' },
    body: { mode: 'raw', raw: JSON.stringify({
      email: pm.environment.get('EMAIL'),
      password: pm.environment.get('PASSWORD'),
      companyId: Number(pm.environment.get('COMPANY_ID'))
    })}
  }, (err, res) => {
    if (!err) pm.environment.set('JWT', res.json().token);
  });
}
```
- Koleksiyon düzeyi başlıklar: `Authorization: Bearer {{JWT}}`, `X-Company-Id: {{COMPANY_ID}}`

## 7) Geriye Dönük Uyum ve Versiyonlama
- Kırıcı değişikliklerde semver kuralı ve deprecate süresi uygulanır (makul bir geçiş süresi, örn. en az bir akış/iteration).
- Header/claim değişikliklerinde geçiş dönemi: her iki adlandırma paralel kabul edilir ve changelog/ADR ile duyurulur.
- Değişiklik kaydı: `docs/05-governance/05-adr/*`, roadmap tarafında ilgili `docs/05-governance/PROJECT_FLOW.md` + Story dokümanları (eski snapshotlar için `docs/05-governance/roadmap-legacy/*`)

## 8) Hızlı Kontrol Listesi
- [ ] `Authorization` başlığı var ve güncel JWT taşıyor
- [ ] `X-Company-Id` gönderiliyor (ve gerekiyorsa `X-Project-Id`, `X-Warehouse-Id`)
- [ ] Endpoint’ler gateway üzerinden `/api/*`
- [ ] Export akışlarında export guard politikası teyit edildi
- [ ] İlgili API referansları incelendi (`docs/03-delivery/api/*.md`)

## 9) Referanslar
- Ortak başlıklar: `docs/03-delivery/api/common-headers.md`
- Users API: `docs/03-delivery/api/users.api.md`
- Auth API: `docs/03-delivery/api/auth.api.md`
- Kimlik (client_credentials): `docs/01-architecture/04-security/identity/02-client-credentials-jwt.md`
- Runbook/izleme: `docs/04-operations/01-runbooks/`, `docs/04-operations/02-monitoring/`

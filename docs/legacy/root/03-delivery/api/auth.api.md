## Auth API (v1) — Login, Kayıt, Şifre İşlemleri

### 1) Login — v1 (yeni)
- Method: POST  
- Path: `/api/v1/auth/sessions`  
- Body:
```json
{ "email": "admin@example.com", "password": "admin1234", "companyId": 1 }
```
- Response:
```json
{
  "token": "<JWT>",
  "email": "admin@example.com",
  "role": "ADMIN",
  "permissions": ["VIEW_USERS", "MANAGE_USERS"],
  "expiresAt": 1762624688000,
  "sessionTimeoutMinutes": 15
}
```

### 2) Kayıt — v1 (yeni)
- Method: POST  
- Path: `/api/v1/auth/registrations`  
- Body:
```json
{ "name": "Yeni Kullanıcı", "email": "new@example.com", "password": "Strong#123" }
```
- Response:
```json
{ "userId": 42, "email": "new@example.com", "status": "PENDING_VERIFICATION", "message": "Kayıt talebiniz alındı. Lütfen e-posta adresinizi doğrulayın." }
```

### 3) Şifre Sıfırlama — v1 (yeni)
- İstek oluştur: `POST /api/v1/auth/password-resets`  
```json
{ "email": "user@example.com" }
```
- Şifreyi güncelle: `POST /api/v1/auth/password-resets/{token}`  
```json
{ "newPassword": "YeniParola#123" }
```

### 4) E-posta Doğrulama — v1 (yeni)
- Method: POST  
- Path: `/api/v1/auth/email-verifications/{token}`

### JWT Claims (özet)
- `sub`: kullanıcı e‑postası  
- `iss`: `auth-service`  
- `aud`: varsayılan `user-service` (çoklu aud desteklenebilir)  
- `userId`, `role`, `permissions[]`, `companyId?`, `sessionTimeoutMinutes`

### Hata Modeli (STYLE-API-001 – ErrorResponse)
```json
{
  "error": "invalid_credentials",
  "message": "Email veya şifre hatalı",
  "fieldErrors": [],
  "meta": { "traceId": "abc-123" }
}
```

### Legacy / Deprecated Uçlar
- `/api/auth/login`, `/api/auth/register`, `/api/auth/forgot-password`, `/api/auth/reset-password`, `/api/auth/verify-email`  
  - Çalışmaya devam eder; v1 uçlarına taşınmaları önerilir.  

### Güvenlik
- Bearer token zorunlu; servis‑içi uçlar için service token’lar `client_credentials` ile mint edilir.  

### Kabul Kriterleri
- `companyId` sağlandığında claim’lerde bağlam döner.  
- FE guard: Kullanıcılar ekranı için `permissions` içinde `VIEW_USERS` olmalı.  

### Bağlantılar
- `docs/03-delivery/api/common-headers.md`
- `docs/03-delivery/guides/API_CLIENT_UPDATES.md`

### 5) Bakım / Vault Outage Yanıtı
- Gateway, Vault erişilmezken `/api/v1/auth/*` çağrılarına 503 döner ve Shell maintenance banner’ının tetiklenmesi için standart hata zarfını kullanır.
- Yanıt:
```http
HTTP/1.1 503 Service Unavailable
Retry-After: 60
X-Serban-Outage-Code: VAULT_UNAVAILABLE
Content-Type: application/json

{
  "error": "vault_unavailable",
  "message": "Kimlik altyapısı devrede değil. Bakım tamamlanınca otomatik denenecek.",
  "fieldErrors": [],
  "meta": {
    "traceId": "abc-123",
    "outageCode": "VAULT_UNAVAILABLE"
  }
}
```
- FE davranışı:
  - Shell login ekranı `outageCode === "VAULT_UNAVAILABLE"` aldığında `MaintenanceBanner` bileşenini gösterir, periyodik retry 60 saniye aralıkla yapılır.
  - Kullanıcı tarafında gösterilen metin: “Kimlik sağlayıcımızda bakım var. Birkaç dakika içinde otomatik olarak yeniden bağlanacağız.”
- Runbook: `docs/04-operations/01-runbooks/vault-failfast-fallback.md` (fail-fast drill & incident adımları).

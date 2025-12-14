## Users API Sözleşmesi (v1)

Amaç: Kullanıcı listeleme/detay uçlarının sözleşmesini, SSRM uyumlu arama/filtre/sıralama parametrelerini tanımlar.

### 1) Listeleme — v1 (yeni)
- Method: GET  
- Path: `/api/v1/users`  
- Query:
  - `page` (>=1), `pageSize` (1..1000)  
  - `search` (ops.) — email/name hızlı arama  
  - `advancedFilter` (ops.) — URL‑encoded JSON (whitelist: name, email, role, enabled, createDate, lastLogin, sessionTimeoutMinutes)  
  - `sort` (ops.) — `field,dir;field2,dir2` (`dir`: `asc|desc`; whitelist alanlar yukarıdakiyle aynı)  
  - `status` (`ACTIVE|INACTIVE|INVITED|SUSPENDED`), `role` (örn. `ADMIN|USER`)
- Response (PagedResult zarfı):
```json
{
  "items": [
    { "id": 1, "name": "John Doe", "email": "john@example.com", "role": "USER", "enabled": true, "createDate": "...", "lastLogin": "..." }
  ],
  "total": 12345,
  "page": 1,
  "pageSize": 50
}
```

### 2) Detay — v1 (yeni)
- Method: GET  
- Path: `/api/v1/users/{id}`  
- Response: `UserDetailDto` (summary + `sessionTimeoutMinutes`)

### 3) Email ile getirme — v1 (yeni)
- Method: GET  
- Path: `/api/v1/users/by-email?email=...`

### 4) Aktivasyon — v1 (yeni)
- Method: PUT  
- Path: `/api/v1/users/{id}/activation`  
- Body:
```json
{ "active": true }
```
- Response:
```json
{ "status": "ok", "auditId": "12345" }
```
  - `auditId` FE’de `/admin/audit?event=<auditId>` deep-link’i için kullanılır.

### Hata Modeli (STYLE-API-001 – ErrorResponse)
```json
{
  "error": "invalid_advanced_filter",
  "message": "Advanced filter JSON geçersiz",
  "fieldErrors": [],
  "meta": { "traceId": "abc-123" }
}
```

### Güvenlik
- Header: `Authorization: Bearer <jwt>`  
- Scope: `X-Company-Id` (ops.), `X-Project-Id`, `X-Warehouse-Id`  

### Legacy / Deprecated Uçlar
- `/api/users/all`, `/api/users/by-email/{email}`, `/api/users/register`, `/api/users/public/register`  
- `/api/users/internal/*` uçları legacy olarak korunur; v1 tasarımına taşınması planlıdır.

### Kabul Kriterleri
- SSRM uyumlu deterministik sayfalama.  
- `advancedFilter` whitelist dışına çıktığında 400 `invalid_advanced_filter`.  
- `items/total/page/pageSize` zarfı FE/BE uyumlu.  

### Bağlantılar
- `docs/01-architecture/03-services/01-user-service.md`
- `docs/01-architecture/01-system/01-backend-architecture.md`

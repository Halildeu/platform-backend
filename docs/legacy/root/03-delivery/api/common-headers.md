## Ortak Başlıklar (Scope & Güvenlik)

Amaç
- Şirket/proje/depo bağlamı gerektiren uçlarda gönderilecek standart başlıkları tanımlamak.

 Başlıklar
- Zorunlu: `X-Company-Id`
- Opsiyonel: `X-Project-Id`, `X-Warehouse-Id`
- Güvenlik: `Authorization: Bearer <jwt>`, (opsiyonel) `X-Internal-Api-Key`

Örnek
```
X-Company-Id: 42
X-Project-Id: 7
Authorization: Bearer eyJ...
```

Bağlantılar
- `docs/03-delivery/api/users.api.md`
- `docs/01-architecture/03-services/01-user-service.md`
- `docs/01-architecture/03-services/02-permission-service.md`

Notlar
- Header isimleri ve güvenlik beklentileri `STYLE-API-001` altında tanımlanan genel API güvenlik standartları ile uyumludur.

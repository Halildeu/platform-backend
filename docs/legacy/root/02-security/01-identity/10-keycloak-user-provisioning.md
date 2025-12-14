# Keycloak ➜ User-Service Provisioning

Bu doküman, Keycloak üzerinde oluşturulan kullanıcıların user-service veritabanına otomatik olarak yansıtılması için sağlanan araçları açıklar.

## 1. Anlık Provision (Admin Flow Adımı)

Keycloak UI’da kullanıcı oluşturduktan hemen sonra aşağıdaki script ile user-service’e kayıt edebilirsiniz:

```bash
cd backend/scripts/keycloak
SERVICE_CLIENT_SECRET=<client_secret> \
./provision-user.sh \
  --email user@example.com \
  --name "Ad Soyad" \
  --role ADMIN \
  --enabled true
```

### Gerekli ortam değişkenleri

| Değişken | Açıklama | Varsayılan |
| --- | --- | --- |
| `SERVICE_CLIENT_SECRET` | client_credentials flow için Keycloak client secret | **zorunlu** |
| `SERVICE_CLIENT_ID` | Keycloak client id | `user-service` |
| `SERVICE_TOKEN_URL` | Token endpoint’i | `http://localhost:8088/oauth2/token` |
| `SERVICE_TOKEN_AUDIENCE` | Service token audience | `user-service` |
| `SERVICE_TOKEN_PERMISSIONS` | Gereken permission claim’i | `users:internal` |
| `USER_SERVICE_URL` | user-service taban URL’si | `http://localhost:8089` |

Script, service token alır ve `/api/v1/users/internal/provision` endpoint’ine `email`, `name`, `role`, `enabled`, `sessionTimeoutMinutes` alanlarını gönderir. Keycloak admin flow’unuza bu script’i dahil ederek kullanıcıyı açar açmaz provision edebilirsiniz.

### Örnek Çalıştırma ve Çıktı

Aşağıdaki örnek, `admin@example.com` kullanıcısını provision etmiş gerçek bir koşunun maskelemiş çıktısıdır:

```bash
$ cd backend/scripts/keycloak
$ SERVICE_CLIENT_SECRET=**** \
  SERVICE_CLIENT_ID=user-service \
  SERVICE_TOKEN_URL=http://localhost:8088/oauth2/token \
  SERVICE_TOKEN_AUDIENCE=user-service \
  USER_SERVICE_URL=http://localhost:8089 \
  SERVICE_TOKEN_PERMISSIONS=users:internal \
  ./provision-user.sh \
     --email admin@example.com \
     --name "Console Admin" \
     --role ADMIN \
     --enabled true

Provision tamamlandı: admin@example.com
```

Kanıt dosyası: `backend/scripts/keycloak/logs/provision-user.admin@example.com.log` (Acceptance §5’te referanslanır).

## 2. Periyodik Senkron Script’i

Keycloak ile user-service arasındaki gap’i kapatmak için aşağıdaki script tüm Keycloak kullanıcılarını tarayıp eksik profilleri oluşturur/günceller:

```bash
cd backend/scripts/keycloak
SERVICE_CLIENT_SECRET=<client_secret> \
KEYCLOAK_ADMIN_USERNAME=admin \
KEYCLOAK_ADMIN_PASSWORD=admin \
./sync-keycloak-users.sh
```

### Ek ortam değişkenleri

| Değişken | Açıklama | Varsayılan |
| --- | --- | --- |
| `KEYCLOAK_BASE_URL` | Keycloak taban URL’si | `http://localhost:8081` |
| `KEYCLOAK_REALM` | Senkron edilecek realm | `serban` |
| `KEYCLOAK_ADMIN_CLIENT_ID` | Admin client id | `admin-cli` |
| `KEYCLOAK_ADMIN_USERNAME` | Admin kullanıcı adı | `admin` |
| `KEYCLOAK_ADMIN_PASSWORD` | Admin parola | `admin` |
| `KEYCLOAK_PAGE_SIZE` | Keycloak API sayfa boyutu | `50` |

Script akışı:

1. Keycloak admin token alınır.
2. `/admin/realms/{realm}/users?briefRepresentation=false` çağrıları ile tüm kullanıcılar çekilir.
3. Her kullanıcı için `provision-user.sh` çağrılarak user-service’e `email`, `name`, `role`, `enabled` bilgileri gönderilir.

> Not: Rolleri belirlemek için önce `attributes.provisionRole[0]` değeri, ardından `realmRoles` içinde `ADMIN` varlığı kontrol edilir. Hiçbiri yoksa rol `USER` olarak set edilir.

### Örnek Çalıştırma ve Çıktı

```bash
$ cd backend/scripts/keycloak
$ SERVICE_CLIENT_SECRET=**** \
  SERVICE_CLIENT_ID=user-service \
  SERVICE_TOKEN_URL=http://localhost:8088/oauth2/token \
  SERVICE_TOKEN_AUDIENCE=user-service \
  SERVICE_TOKEN_PERMISSIONS=users:internal \
  KEYCLOAK_ADMIN_USERNAME=admin \
  KEYCLOAK_ADMIN_PASSWORD=admin \
  KEYCLOAK_BASE_URL=http://localhost:8081 \
  KEYCLOAK_REALM=serban \
  ./sync-keycloak-users.sh

>> Keycloak kullanıcıları senkronize ediliyor (first=0, batch=3)
Provision tamamlandı: admin@example.com
   - e-posta biçimi geçersiz (serban-admin), kullanıcı atlandı.
   - e-posta biçimi geçersiz (serban-viewer), kullanıcı atlandı.
Toplam 1 kullanıcı senkronize edildi.
```

Script, e-posta adresi bulunmayan servis hesaplarını otomatik olarak atlar; yalnız RFC uyumlu adresi olan gerçek kullanıcılar user-service tarafına taşınır. Çalıştırma logu `backend/scripts/keycloak/logs/sync-keycloak-users.log` dosyasında saklanır ve Acceptance §5’te referanslanır.

## API Değişiklikleri

- `user-service`: Yeni `POST /api/v1/users/internal/provision` endpoint’i, `KeycloakUserProvisionRequest` JSON’ını kabul eder ve service token’ı üzerinde `PERM_users:internal` izni arar. Bu endpoint hem anlık script hem de senkron script tarafından kullanılmaktadır.

Bu iki araç sayesinde hem manuel admin akışıyla hem de düzenli cron job ile Keycloak ⇄ user-service kullanıcı kayıtlarını senkron tutabilirsiniz.

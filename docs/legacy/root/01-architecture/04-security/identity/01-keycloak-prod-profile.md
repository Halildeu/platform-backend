## Keycloak Tek Issuer (Stage/Prod) Profili

Amaç
- Stage/Prod ortamlarında tek issuer olarak Keycloak’ı kullanmak; dev’de çoklu‑issuer kabulü açık kalabilir.

Gateway/env
```
SECURITY_JWT_JWK_SET_URI=<KC_JWKS>
SECURITY_JWT_ISSUER=<KC_ISS>
# Çoklu-issuer kapalı: SECURITY_JWT_JWK_SET_URIS/SECURITY_JWT_ISSUERS tanımlamayın
```

Servisler (user/permission/variant)
```
SECURITY_JWT_JWK_SET_URI=<KC_JWKS>
SECURITY_JWT_ISSUER=<KC_ISS>
SECURITY_JWT_AUDIENCE=<service-name>
# Çoklu-issuer kapalı
```

Keycloak ayarları (özet)
- Client Scopes: `aud-user-service`, `aud-permission-service` → Included Custom Audience
- Clients: ilgili service/gateway; gerekli scope’ları “Assigned Default Client Scopes” olarak ekleyin.

Notlar
- Dev Compose’da çoklu‑issuer örneği korunur (auth-service + Keycloak). Stage/Prod pipeline’da yalnız Keycloak değişkenlerini enjekte edin.

Uygulamalı Adımlar (Admin Console)
1) Realm seçin (örn. `platform`)
2) Client Scope oluşturun (audience için)
   - Client Scopes → Create → Name: `aud-user-service` → Protocol: `openid-connect`
   - İçeride Mappers → Create → Mapper Type: `Audience`
     - Name: `aud-user-service`
     - Included Custom Audience: `user-service`
     - Add to access token: `ON`
   - Aynı şekilde `aud-permission-service` (Included Custom Audience: `permission-service`)
3) Clients (gateway, frontend, vs.)
   - İlgili client’ı açın → Client Scopes → `Assigned Default Client Scopes` listesine `aud-user-service` ve gerekiyorsa `aud-permission-service` ekleyin.
   - (Opsiyonel) Service accounts ve client_credentials kullanılacaksa `Service Accounts Enabled=ON` ve gerekli role mapping.
4) Token doğrulama
   - JWKS: `<KEYCLOAK_BASE>/realms/<REALM>/protocol/openid-connect/certs`
   - Issuer: `<KEYCLOAK_BASE>/realms/<REALM>`

Stage/Prod ENV Örnekleri
- bkz. `docs/03-delivery/01-env/01-.env.stage.local`, `docs/03-delivery/01-env/02-.env.prod.local`
- Not: Stage/Prod’da `SPRING_CLOUD_VAULT_FAIL_FAST=true` ve Vault URI’leri zorunlu olmalı; uygulama tüm gizli bilgileri Vault’tan okur (DB cred, service-secret, JWT key).

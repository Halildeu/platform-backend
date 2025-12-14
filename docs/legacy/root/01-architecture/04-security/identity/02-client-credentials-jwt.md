# Client Credentials JWT Geçiş Planı

`INTERNAL_API_KEY` kullanımının kaldırılıp her servis için OAuth 2.0 client_credentials akışına geçilmesine dair teknik plan. JWKS/KID yönetimi ve uygulama entegrasyon adımlarını içerir. Sprint 3 için “done means removed” gating kriterleri bu dökümanda netleştirilmiştir.

## 1. Amaç ve Kapsam
- Paylaşılan statik API anahtarını (ENV: `INTERNAL_API_KEY`) üretimden kaldırmak.
- Servisler arası çağrıları Keycloak (veya dahili Auth Service) üzerinden client_credentials ile yetkilendirmek.
- JWT imzalama anahtarlarının JWKS uç noktası üzerinden dağıtımı; KID tabanlı anahtar rotasyonu.
- Faz 1’de staging → production rollout; audit loglarda servis kimliği ayırt edilebilir olacak.

## 2. Hedef Mimari
1. Her servis için Keycloak’ta (realm: `services`) `confidential` client tanımı.
2. Client secret Vault’da `secret/{env}/{service}/oauth/client-secret` path’inde saklanır.
3. Servisler Vault üzerinden secret’ı çekip Auth Service’e `client_credentials` token isteği yapar (mint by auth-service).
4. Auth Service kullanıcı ve servis JWT’lerini `kid` alanı ile RS256 imzalar ve `/oauth2/jwks` altında JWKS yayımlar.
5. Gateway ve servisler JWKS cache edip KID eşleşmesiyle doğrulama yapar; key cache süresi 5 dk.

## 3. Keycloak Konfigürasyonu
- Realm: `services`
- Örnek client tanımı:
  - `clientId`: `permission-service`
  - `clientAuthenticatorType`: `client-secret`
  - `serviceAccountsEnabled`: `true`
  - `defaultClientScopes`: `service-default`
  - Role mapping: `svc:permission-service`
- Token ayarları:
  - Access Token TTL: 5 dk (Faz 1), Refresh Token devre dışı.
  - Signature alg: `RS256` (kullanıcı ve servis token’ları)
  - JWKS URL: `https://auth.{env}.corp/oauth2/jwks`

### Provisioning Script (örnek)
```bash
#!/usr/bin/env bash
set -euo pipefail

KEYCLOAK_BASE="https://keycloak.{ENV}.corp"
ADMIN_TOKEN="$(get_admin_token)"

create_client() {
  local client_id="$1"
  curl -s -X POST "${KEYCLOAK_BASE}/admin/realms/services/clients" \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    -H "Content-Type: application/json" \
    -d '{
      "clientId": "'${client_id}'",
      "name": "'${client_id}'",
      "protocol": "openid-connect",
      "serviceAccountsEnabled": true,
      "publicClient": false,
      "secret": "'$(openssl rand -hex 24)'"
    }'
}
```

> Gerçek script Terraform/Keycloak provider ile idempotent hale getirilecek.

## 4. Servis Tarafı Değişiklikleri
1. `INTERNAL_API_KEY` bağımlılıklarının kaldırılması.
   - Config: Üretim/stage konfigürasyonunda `internal.api-key` tutulmaz; yalnızca local profil için opsiyonel ve `security.legacy-api-key.enabled=false` varsayılanıyla kapalıdır. Container ortamında eşdeğer env: `SECURITY_LEGACY_API_KEY_ENABLED=false`.
   - Kodda header doğrulama logic’i `Bearer <token>` JWT doğrulaması ile değiştirilecek.
2. Vault’tan client secret çekme:
   - Spring Cloud Vault ile `secret/{env}/{service}/oauth/client-secret` path’i.
   - Secret yoksa fail-fast; log + alert (`docs/vault-config.md` içinde atıf).
   - RS256 key çifti Vault’ta saklanır (`service-jwt/private-key`, `service-jwt/public-key`); detaylar için `docs/service-jwt-keys.md`.
3. Token alma helper:
   - `ServiceTokenProvider` auth-service üzerinden HTTP ile token mint eder (`POST /oauth2/token`, `grant_type=client_credentials`).
   - Konfig: `security.service-token.client.token-url`, `client-id`, `client-secret`; ayrıca `audience`, `permissions`, `ttl-seconds`.
   - Service-to-service kimlik claim’leri: `svc=<service>`, `env=<env>`, `perm=[permissions]`, `aud=<target>`.
   - Güvenlik: Auth-service SecurityConfig’te `/oauth2/token` `permitAll` olarak tanımlıdır; kimlik doğrulama `Basic <base64(clientId:secret)>` veya form alanlarıyla yapılır. İzinli audience/permission değerleri allowlist ile sınırlandırılır; yanlış isteklerde `401/400` döner.
4. Gateway ve servis JWT filtreleri:
   - JWKS cache implementasyonu (Nimbus JOSE/JWT).
   - KID bulunamadığında JWKS yeniden fetch (retry backoff).
   - Algoritma kısıtlaması: yalnız RS256 kabul edilir.
   - User-service internal endpoint'leri `aud=user-service` ve `perm=users:internal` claim'lerini zorunlu kılar (`security.service-auth.*` + `security.service-auth.jwk-set-uri`).

## 9. Pratik (Bu Repo İçin)

- Token mint URL (Keycloak):
  - `https://keycloak.{env}.example.com/realms/platform/protocol/openid-connect/token`
  - Grant: `client_credentials`
  - Auth: `client_id` + `client_secret` (service account)

- Örnek curl:
```
CLIENT_ID=permission-service
CLIENT_SECRET=***
KC_TOKEN=https://keycloak-stage.example.com/realms/platform/protocol/openid-connect/token

curl -s -X POST "$KC_TOKEN" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d "grant_type=client_credentials&client_id=${CLIENT_ID}&client_secret=${CLIENT_SECRET}" | jq -r '.access_token'
```

- Stage/Prod env anahtarları (örnek):
  - `SECURITY_JWT_JWK_SET_URI`, `SECURITY_JWT_ISSUER` → Keycloak
  - `SECURITY_JWT_AUDIENCE` → hedef servis adı (örn. `permission-service`)
  - Vault: `secret/{env}/{service}/oauth/client-secret` path’inde saklayın; CI/CD ile enjekte edin.

### 9.1 Vault Stage/Prod Script + Pipeline Smoke

1. **Secret yazımı:** `scripts/vault/write-secrets-stage.sh` default olarak `ENV=stage` ile çalışır. Prod için wrapper eklendi:  
   ```bash
   # Stage
   VAULT_ADDR=https://vault-stage.example.com \
   VAULT_TOKEN=<stage-token> \
   SERVICE_CLIENT_USER_SERVICE_SECRET=<secret> \
   ./scripts/vault/write-secrets-stage.sh

   # Prod
   VAULT_ADDR=https://vault.example.com \
   VAULT_TOKEN=<prod-token> \
   SERVICE_CLIENT_USER_SERVICE_SECRET=<secret> \
   ./scripts/vault/write-secrets-prod.sh
   ```
   Script aynı zamanda opsiyonel olarak permission/variant DB credential’larını da aynı KV path’lerine yazar; boş değer gönderirseniz mevcut kayıtlar korunur.
2. **Fallback kontrolü:** Secret yazıldıktan sonra Auth Service doğrudan Vault üzerinden okur; env fallback’i yalnız local profil içindir. Stage/prod rollout’larında fallback’in devreye girmediğini doğrulamak için Vault path’inin dolu olduğunu ve pod env’lerinde `SERVICE_CLIENT_USER_SERVICE_SECRET` tanımlı olmadığını kontrol edin.
3. **Smoke testi:** Pipeline veya lokal doğrulamada secret’ı tekrar Vault’tan okuyup `/oauth2/token` zincirini test etmek için `scripts/smoke.sh` artık aşağıdaki env’leri destekler:
   ```bash
   USER_JWT=<real-user-token> \
   VAULT_ADDR=https://vault-stage.example.com \
   VAULT_TOKEN=<stage-token> \
   SERVICE_TOKEN_VAULT_ENV=stage \
   SERVICE_TOKEN_VAULT_SERVICE=auth-service \
   ./scripts/smoke.sh
   ```
   Script secret bulunamazsa env `SERVICE_TOKEN_CLIENT_SECRET`’e bakar, o da yoksa yalnız local için `dev-secret`e düşer. Böylece CI’da `/oauth2/token` ve gateway çağrıları gerçek secret ile doğrulanır.
4. **Health monitörü:** Compose/K8s rollout’larında `docker compose ps` veya `kubectl get pods` ile Keycloak/Vault healthcheck statülerini izleyin. Vault için `vault status --address=$VAULT_ADDR`, Keycloak için `/realms/serban/health` HTTP 200 olmalı; pipeline smoke aşaması bunlar başarısızsa kırmızıya düşürülmelidir.
5. **CI entegrasyonu:** `.github/workflows/env-smoke.yml` workflow’u stage/prod rollout’larından sonra otomatik olarak `write-secrets-*.sh` + `scripts/health/check-identity.sh` + `scripts/smoke.sh` adımlarını çağırır. Detaylar için `docs/03-delivery/02-ci/03-env-smoke.md`.

## 4.1 RS256 + JWKS Benimseme Matrisi (mevcut durum)

- `permission-service`: Resource Server + JWKS (RS256) aktif; kullanıcı JWT HS fallback yalnız local profilde.
- `user-service`: Resource Server + JWKS (RS256) aktif; servis token üretimi mint-by-auth-service (client_credentials).
- `variant-service`: Özel `JwtAuthFilter` ve HS secret ile doğrulama; RS256 + JWKS’e geçirilecek.
- `api-gateway`: JWT pre-auth filtresi planlı (feature flag ile); zorunlu devreye alım ayrı flow’da.

> Not: Sprint 3 kapsamı, servislerin (en azından internal çağrı yüzeyleri) RS256 + JWKS doğrulamayı zorunlu kılacak şekilde güncellenmesini hedefler. Kullanıcı JWT için HS384 → RS256 geçişi ayrı bir fazda ele alınabilir (bkz. jwt-rotation-plan).

## 5. JWKS & KID Yönetimi
- Anahtar üretimi: Vault Transit veya OpenSSL ile RSA 2048/4096; Faz 2’de HSM/KMS.
- JWKS dokümanında aktif anahtar (`status=active`) ve `next` anahtar (`status=staging`) tutulur.
- Rotasyon planı:
  1. Yeni anahtar oluştur, JWKS’e `staging` KID ile ekle.
  2. Servisler JWKS’i cache’lerken `staging` anahtarı indirir.
  3. Geçiş günü `staging` anahtar `active` yapılır; eski anahtar `deprecated` → 24 saat sonra kaldır.
- Monitor:
  - JWKS fetch hataları için prometheus metriği (`jwks_fetch_failures_total`).
  - Token doğrulama hataları audit log’a servis ID, claim detayları ile yazılır.

## 6. Rollout Planı
1. **Hazırlık**
   - Tüm servisler için Keycloak client oluştur, secret Vault’a yaz.
   - `policy-templates/` kullanarak gerekli Vault politikalarını uygula.
   - Gateway ve hedef servislerde JWT doğrulama kodu hazırlanıp feature flag ile kapalı tutulur.
2. **Staging Testleri**
   - Internal API key header’ı devre dışı bırak, sadece JWT ile çağrıları çalıştır.
   - Expired token, claim mismatch, yanlış audience testlerini otomasyona ekle.
3. **Production Geçişi**
   - Deploy sonrası `INTERNAL_API_KEY` header’ı kabul eden kodu kapat (yalnız local profillerde açık kalır).
   - Ortam değişkeni ve CI secret kasasında anahtar kalmadığını doğrula.
   - Audit log’da servis kimliklerinin doğru göründüğünü kontrol et.
4. **Temizlik**
   - Docker Compose, Helm chart vb. konfiglerden `INTERNAL_API_KEY` referanslarını kaldır.
   - Runbook’ta kill-switch bölümü güncellenir.
   - CI/CD pipeline’larında hardcoded anahtar taraması yapılır (`gitleaks`).

## 7. Gereken Artifacts
- Terraform/Keycloak provisioning dosyaları (`infra/keycloak-services.tf` önerilir).
- Servis başı Spring config değişiklik PR’ları.
- Gateway JWT doğrulama modülü (`gateway-jwt-filter.md`).
- Test raporu: Expired/claim mismatch senaryoları.

---
## 7.1 Gateway ve Servis JWT Doğrulama / Fail-Fast Stratejisi

### Gateway (Edge) Katmanı
- **JWKS Ön Isıtma:** Gateway açılışında `security.service-auth.jwk-set-uri` üzerinden JWKS’i indirip bellekten hizmet verir. JWKS’e ulaşılamazsa boot fail-fast davranır (deployment rollback) ve uyarı gönderilir.
- **İmza Doğrulama:** Sadece `alg = RS256` ve `kid` eşleşen anahtarlar kabul edilir. `aud` ve `svc` claim’leri rota bazlı whitelist ile doğrulanır (örn. `/api/users/**` → `aud=user-service`).
- **Fail-Fast Kuralları:**
  - JWKS refresh hatası ≥3 (backoff dahil) → istekler için 503 döndür, `X-Error-Code: jwks_unavailable` header’ı ekle.
  - Token hatalıysa (expired, signature, aud/scope mismatch) → 401/403, açıklayıcı `reason` ve `svc` claim’i audit log’una yaz.
- **Degrade Modu:** Son geçerli JWKS cache’i varsa maksimum 5 dakika “stale-but-valid” olarak kullan; bu süre aşılırsa trafiği 503’e çek ve PagerDuty uyarısı bas. Stale kullanım sırasında metriklerde `jwks_cache_stale=1` etiketi tutulur.

### Dahili Servisler
- **Spring Security Yapılandırması:** `NimbusJwtDecoder` JWKS URI ile tanımlanır; boş sonuç veya KID bulunamazsa istek 503 ile reddedilir (fail-fast).
- **Local Profil Hariç Fallback Yok:** `INTERNAL_API_KEY` filtresi kaldırıldığı için JWT doğrulaması geçilemez; local profil dışında degrade yok.
- **Erişim Politikası:** `aud` ve `svc` claim’leri `security.service-auth.allowed-audiences` ve `security.service-auth.allowed-services` üzerinden doğrulanır; mismatch → 403.
- **JWKS Yenileme:** Decoder 60 saniye aralıklarla JWKS’i yeniler; yenilemeden 3 ardışık hata alınırsa circuit breaker devreye girer ve çağrı 503’e düşer.
- **Gözlemlenebilirlik:** `jwt_validation_failure_total{reason="signature"}` ve `jwks_refresh_failures_total` metrikleri Prometheus’a basılır; log’lar audit hattına yazılır.
- **Graceful Shutdown:** Servis kapanmadan önce JWKS watcher kapatılır; böylece gereksiz uyarılar tetiklenmez.

## 8. Sprint 3 Gating — “Done Means Removed”

Zorunlu kabul kriterleri (hepsi sağlanmadan kapanış yapılamaz):

1. Kod/konfig/CI’de `INTERNAL_API_KEY` referansı yok.
   - CI gate: `verify-no-internal-key` script’i `.java`, `.ts`, `.yml` içinde tarayıp build’i fail eder.
   - Compose/Helm: `SECURITY_LEGACY_API_KEY_ENABLED=false` (prod/stage) ve legacy path’ler kapalı.
2. Servisler RS256 + JWKS ile doğrulama yapıyor (internal yüzeyler):
   - `permission-service`: JWKS decoder etkin, legacy internal key filtresi kapalı.
   - `user-service` ve `variant-service`: HS secret tabanlı doğrulama kaldırıldı veya yalnız local profile sınırlandı; RS256 + JWKS etkin.
3. Güvenlik testleri (staging):
   - Unauthorized/expired/claim mismatch → 401/403; JWKS unreachable → circuit breaker / 503; raporlar CI artefact’larında.
4. Gözlemlenebilirlik:
   - Grafana’da `jwt_validation_failure_total`, `jwks_fetch_duration_seconds` metrikleri ve alarm eşikleri tanımlı.
   - Audit log’larda reddedilen token nedenleri (`reason`) ve `svc` claim’i izlenebilir.
5. Operasyonel temizlik:
   - CI kasaları ve Vault’ta `INTERNAL_API_KEY` secret’ları kaldırıldı (audit kaydı mevcut).
   - Runbook güncellendi: bkz. `docs/04-operations/01-runbooks/43-kill-switch-plan.md`.

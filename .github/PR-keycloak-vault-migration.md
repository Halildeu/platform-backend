Title: Keycloak Tek Issuer + Vault Only (Dev/Stage/Prod) | ENV Örnekleri | Provisioning Scriptleri

Özet
- Stage/Prod için tek issuer=Keycloak profilini dokümante ettim; ENV örneklerine “Vault only / fail-fast=true” notunu ekledim.
- Dev’i de Vault‑only (fail‑fast=true) moduna çektim; KV yazım scriptleri ve smoke testleri eklendi.
- Keycloak client/scope provisioning için admin API tabanlı script eklendi; (ops.) secret’ı Vault’a yazıyor.

Değişiklikler
- docs/02-security/01-identity/01-keycloak-prod-profile.md: Uygulamalı adımlar.
- docs/03-deploy/01-env/01-.env.stage.local, 02-.env.prod.local: JWKS/ISS ve DB/Vault örnek anahtarlar.
- docs/02-security/02-vault/01-vault-dev-quickstart.md: Dev hızlı kurulum, Vault‑only (fail‑fast) akışı.
- scripts/keycloak/provision-stage.sh: Realm + client scopes (audience) + clients + (ops.) Vault yazımı.
- scripts/vault/dev-init.sh: Dev KV bootstrap.
- scripts/vault/write-secrets-stage.sh: Stage/Prod KV yazımı.
- scripts/smoke/keycloak-client-credentials.sh: client_credentials token smoke.

Test / Doğrulama
1) Dev
   - `docker compose up -d keycloak vault`
   - `scripts/vault/dev-init.sh`
   - Login → TOKEN → `GET /api/users/all` 200 (gateway üzerinden)
2) Stage (örnek)
   - Keycloak provisioning: `ENV=stage KEYCLOAK_BASE=… KC_ADMIN_USER=… KC_ADMIN_PASS=… ./scripts/keycloak/provision-stage.sh`
   - Vault secrets: `ENV=stage VAULT_ADDR=… VAULT_TOKEN=… ./scripts/vault/write-secrets-stage.sh`
   - Smoke: `KEYCLOAK_TOKEN_URL=… CLIENT_ID=permission-service CLIENT_SECRET=… ./scripts/smoke/keycloak-client-credentials.sh`

Geri Dönüş
- Dev: Gerekirse geçici olarak `SPRING_CLOUD_VAULT_ENABLED=false` ile env fallback (önerilmez).
- Stage/Prod: ENV revert; issuer’ı önceki JWKS’e geri çevirin.
- Not: Dev/Stage/Prod `SPRING_CLOUD_VAULT_FAIL_FAST=true` → Vault erişimi yoksa servis başlamaz.

Doc Policy
- [x] API davranışı değişmedi; yalnız güvenlik konfigürasyonu dokümante edildi.
- [x] docs/security/* eklendi/güncellendi
- [x] docs/agent/BACKEND.md referansları (gerekliyse) güncellenecek
- [x] no-doc-impact değil

Riskler / Notlar
- Çoklu‑issuer sadece dev’de açık; stage/prod’da tek issuer kullanılmalı.
- Vault dev’de HTTP+root; prod’da TLS + AppRole/JWT auth zorunlu.
- Stage/Prod gizli değerler yalnız Vault’ta tutulur (`secret/<env>/...` yolları).

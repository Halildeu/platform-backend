## Vault Dev Quickstart (Vault-only, Fail-Fast)

Amaç
- Dev ortamını Vault-only çalışacak şekilde (fail-fast=true) ayarlayıp gizli bilgileri merkezileştirmek.

Compose
- `docker-compose.yml` içine `vault` servisi eklendi (HashiCorp Vault dev mode, token: `root`).
- Servislerde (auth-service, user-service, permission-service):
  - `SPRING_CLOUD_VAULT_ENABLED=true`
  - `SPRING_CLOUD_VAULT_FAIL_FAST=true`
  - `SPRING_CLOUD_VAULT_KV_ENABLED=true`
  - `VAULT_ADDR=http://vault:8200`, `SPRING_CLOUD_VAULT_TOKEN=root`, `SPRING_CLOUD_VAULT_SCHEME=http`

Başlatma
```
docker compose up -d vault
scripts/vault/dev-init.sh
```

Ne yazılıyor?
- `secret/permission-service`: `PERMISSION_DB_USERNAME`, `PERMISSION_DB_PASSWORD`
- (ops.) `secret/auth-service`: `security.service-jwt.*` anahtarları (PEM yollarını env ile verin)

Doğrulama
```
curl -s -H 'X-Vault-Token: root' http://localhost:8200/v1/secret/metadata/permission-service
```

Notlar
- Dev’de HTTP ve root token ile çalışıyoruz; prod/stage’de AppRole/JWT auth ve TLS zorunlu.
- Dev’de artık fail-fast=true. Vault kapalıyken servisler başlamaz; bu, “Vault tek kaynak” prensibini lokalde de uygular.
- Stage/Prod’da da fail-fast=true; gizli bilgilerin tek kaynağı `secret/<env>/...` path’leri olmalıdır.

# Stage/Prod Smoke Pipeline

Bu repo artık GitHub Actions içinde `Env Smoke` workflow’u (`.github/workflows/env-smoke.yml`) ile stage/prod rollout sonrası doğrulama yapıyor. Workflow elle tetiklenebilir (`workflow_dispatch`) ve `target_env` olarak `stage` veya `prod` seçilebilir. Seçilen ortam aynı isimli GitHub Environment’a bağlanır; secrets/vars burada saklanır.

## Gerekli Secrets/Vars

| Environment Secret / Var | Açıklama |
| --- | --- |
| `VAULT_ADDR` / `VAULT_TOKEN` | Vault endpoint ve erişim token’ı (AppRole veya CI token). |
| `SERVICE_CLIENT_USER_SERVICE_SECRET` | Auth Service → `user-service` client credential secret’ı; script Vault’a yazar. |
| `PERMISSION_DB_USERNAME/PASSWORD` & `VARIANT_DB_USERNAME/PASSWORD` | Opsiyonel; set edilmezse Vault KV yazımı skip edilir. |
| `USER_JWT` | Gateway üzerinden kullanıcı çağrılarını doğrulamak için kalıcı test kullanıcısının JWT’si. |
| `KEYCLOAK_HEALTH_URL` (var) | Keycloak health endpoint’i (`https://keycloak.stage/realms/serban/health`). |
| `KEYCLOAK_AUTH_HEADER` (secret) | Health endpoint auth istiyorsa (örn. `Authorization: Bearer <token>`). |
| `AUTH_BASE_URL`, `GATEWAY_BASE_URL`, `OAUTH2_JWKS_URL` (var) | Stage/prod URL’leri. JWKS URL verilmezse script `AUTH_BASE_URL` üzerinden üretir. |
| `SERVICE_TOKEN_CLIENT_ID/AUDIENCE/PERMISSIONS` (var) | Smoke sırasında mint edilecek service token konfigi. |

> Not: Secrets environment seviyesinde tanımlanır; `stage` ve `prod` için aynı isimleri kullanın. Varsayılanlar `user-service`, `permission-service`, `permissions:read` şeklindedir.

## Workflow Adımları
1. **Sync Vault secrets** – `write-secrets-stage.sh` / `write-secrets-prod.sh` çağrılarak `security.service-clients.user-service` alanı ilgili KV path’ine yazılır. DB cred env’leri boşsa script secret’ı değiştirmez.
2. **Identity health checks** – `scripts/health/check-identity.sh` ile `vault status` HTTP endpoint’i ve Keycloak health endpoint’i doğrulanır. Bu adım fail ederse smoke koşmaz.
3. **Gateway smoke** – `scripts/smoke.sh` artık URL parametreleri ve Vault tabanlı client secret fetch’ini desteklediğinden CI pipeline’ında `/oauth2/token`, JWKS ve gateway çağrıları doğrulanır.

## Kullanım

1. GitHub Actions → *Env Smoke* workflow’unu seçin.
2. `Run workflow` butonu ile `target_env=stage` veya `prod` seçimini yapın.
3. Workflow sonuçları:
   - `Sync Vault secrets`: yazılan KV path’leri log’da görünür.
   - `Identity health checks`: Vault `initialized/sealed` bilgileri ve Keycloak HTTP 200 çıktısı.
   - `Gateway smoke`: JWKS, `client_credentials`, `/api/users/all`, `/api/users/export.csv` HTTP kodları.
4. Adım başarısız olursa GitHub Actions error mesajını PagerDuty/Slack bildirim mekanizmanıza bağlayın.

Bu pipeline; Vault secret’ın doğru ortamda bulunduğunu, `/oauth2/token` zincirinin izin verdiğini ve gateway rotalarının erişilebilir olduğunu stage/prod deployment’larından sonra otomatik olarak doğrular. Ops ekibi `scripts/health/check-identity.sh` çıktısını Grafana/Alertmanager panellerine taşıyarak Vault & Keycloak availability metriklerini izleyebilir. 
Detaylı alerting önerileri için `docs/04-operations/02-monitoring/01-identity-alerting.md` dokümanına bakın.

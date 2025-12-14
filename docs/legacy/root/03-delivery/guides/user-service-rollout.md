# User-Service Rollout Checklist (Service Token Secret)

Amaç: Stage/prod deployment’larında `user-service`’in Auth Service’e aynı client credential ile bağlandığını doğrulamak. Client secret kaynağı Vault (tercih) veya acil durumda env değişkeni olabilir.

## 1. Vault Path
- KV path: `secret/<env>/auth-service`
- Key: `security.service-clients.user-service`
- Sync: GitHub Actions’daki `Env Smoke` workflow’u veya manuel olarak `scripts/vault/write-secrets-stage.sh` / `write-secrets-prod.sh`.
- Doğrulama:
  ```bash
  VAULT_ADDR=... VAULT_TOKEN=... ENV=stage \
    curl -s -H "X-Vault-Token: $VAULT_TOKEN" \
    "$VAULT_ADDR/v1/secret/data/$ENV/auth-service" | jq '.data.data["security.service-clients.user-service"]'
  ```

## 2. Pod / Deployment Ayarları
- `SPRING_CLOUD_VAULT_*` env’leri set edilmiş olmalı (URI, token/AppRole, kv-enabled).
- Service token property `security.service-token.client.client-secret` **env üzerinden set edilmeyecek**; Vault otomatik bind eder.
- Vault Agent / init container kullanıyorsanız, `/config/application.properties` içine secret’ın map edildiğinden emin olun.
- Acil durum fallback’i için (Vault erişilemezse) deployment manifest’ine `SERVICE_TOKEN_CLIENT_SECRET` env ekleyebilir, fakat rollout sonrası kaldırmanız önerilir.

## 3. Doğrulama (Pods)
1. `kubectl exec deploy/user-service -c user-service -- printenv SERVICE_TOKEN_CLIENT_SECRET` → boş olmalı (fallback kullanılmıyor).
2. `kubectl logs deploy/user-service | grep "security.service-clients.user-service"` → değer log’lanmaz, fakat Vault binding hatası yok.
3. `scripts/verify-service-jwt-env.sh` ile runtime’da JWKS/secret doğrulamasını yapın (script env olarak pod’dan alınan token’ı kullanır).

## 4. Pipeline Smoke
- `Env Smoke` workflow’u `scripts/smoke.sh` ile `/oauth2/token` + gateway rotalarını kontrol ederek rollout’un tutarlı olduğunu garanti eder.
- Workflow başarısız ise:
  1. Vault path’ini ve policy’yi kontrol edin.
  2. `user-service` pod’larında Vault bağlantısı (`vault-token` secret) hatası olup olmadığını inceleyin.
  3. Gerekirse fallback env ile yeniden deploy edin, ancak ardından Vault erişimi düzeltilmelidir.

Bu checklist’i release runbook’unuza ekleyin; böylece client secret kaynağı değiştiğinde bile user-service rollout’larının davranışı deterministik kalır. 

# Auth Service Vault AppRole Rehberi

Amaç: Stage/prod ortamlarında auth-service pod/VM’lerinin yalnız kendi KV path’lerini okuyabileceği AppRole tanımlamak ve CI rotasyon job’u için ayrı politika kullanmak.

## 1. Politikalar

- `infra/vault/policies/auth-service.hcl`: runtime AppRole (okuma/listeme)
- `infra/vault/policies/auth-service-rotation.hcl`: CI/rotasyon (create/update)

`{{env}}` placeholder’ını `stage` veya `prod` ile değiştirin:

```bash
ENV=stage
envsubst < infra/vault/policies/auth-service.hcl > /tmp/auth-service-${ENV}.hcl
vault policy write auth-service-${ENV} /tmp/auth-service-${ENV}.hcl

envsubst < infra/vault/policies/auth-service-rotation.hcl > /tmp/auth-service-${ENV}-rotation.hcl
vault policy write auth-service-${ENV}-rotation /tmp/auth-service-${ENV}-rotation.hcl
```

## 2. AppRole Oluşturma

```bash
ENV=stage
ROLE_NAME=auth-service-${ENV}
vault write auth/approle/role/${ROLE_NAME} \
  policies="auth-service-${ENV}" \
  token_ttl="24h" token_max_ttl="72h" \
  secret_id_ttl="24h" secret_id_num_uses=0

# CI rotasyon rolü
vault write auth/approle/role/${ROLE_NAME}-rotation \
  policies="auth-service-${ENV}-rotation" \
  token_ttl="15m" token_max_ttl="30m" \
  secret_id_ttl="15m" secret_id_num_uses=1
```

Role ID / Secret ID’yi güvenle saklayın (K8s secret veya GitHub Environment secret). Örnek:

```bash
vault read -field=role_id auth/approle/role/${ROLE_NAME}/role-id
vault write -f auth/approle/role/${ROLE_NAME}/secret-id -format=json \
  | jq -r '.data.secret_id'
```

## 3. Doğrulama

1. AppRole ile login olun:
   ```bash
   vault write auth/approle/login role_id=<ROLE_ID> secret_id=<SECRET_ID>
   ```
   Dönen client token’ı `VAULT_TOKEN` olarak kullanın.
2. Beklenen path’i okuyun:
   ```bash
   VAULT_ADDR=... VAULT_TOKEN=... \
   vault kv get secret/${ENV}/auth-service | jq '.data'
   ```
3. Yetkisiz path’e erişimin engellendiğini doğrulayın:
   ```bash
   vault kv get secret/${ENV}/permission-service
   # => permission denied
   ```

Bu adımları hem `stage` hem `prod` için uygulayın. Token/secret-id rotasyonunu `docs/agents/03-secret-rotation-pipeline.md` adımlarına göre otomatik hale getirebilirsiniz. 

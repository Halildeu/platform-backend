# AGENT NOTU – Normatif Kurallar
> Bu dosya `docs/agents` altında yer alan resmi secret rotasyonu kural setlerinden biridir.  
> LLM agent, güvenlik/compliance ile ilgili SPEC, STORY veya RUNBOOK üretirken buradaki maddeleri “MUST” olarak kabul eder; yalnız uygular/özelleştirir, yeniden tanımlamaz.  
> Kural değişikliği gerekiyorsa bu dosyanın kendisi review/commit ile güncellenir; başka dokümanlarda override yapılmaz.

# Secret Rotasyon Pipeline Tasarımı

Faz 2 kapsamında secret’ların (JWT, DB creds, üçüncü parti anahtarlar) CI’da tutulmadan deployment sırasında yenilenmesi.

## 1. İlkeler
- CI pipeline’da hiçbir secret plaintext olmayacak; yalnız placeholder.  
- Deployment sırasında init container / Vault Agent aracılığıyla secret çekilecek.  
- Rotasyon pipeline’ı `security.service-token.permissions` kullanarak Vault’a yazma yetkisine sahip.

## 2. Pipeline Akışı
1. Git push → CI build (unit test, static check).  
2. Pre-deploy job: `scripts/verify-service-jwt-env.sh`, `vault status`, `vault kv get ...`.  
3. Rotasyon job (ops tarafından tetiklenir):  
   - Secret türüne göre script (`rotate-service-jwt.sh`, `db-rotate.sh`, `third-party-key.sh`).  
   - `vault kv patch secret/{env}/{service}/{name} value=new` veya dynamic engine call.  
4. Deployment (K8s ArgoCD/Helm): pod rollout + init container/sidecar secret fetch.  
5. Post-deploy validation: smoke tests, JWKS check, DB connection test.

## 3. K8s Örnek (Init Container)
```yaml
initContainers:
  - name: fetch-secrets
    image: hashicorp/vault:1.17
    env:
      - name: VAULT_ADDR
        value: https://vault.service.${ENV}.corp:8200
      - name: VAULT_TOKEN
        valueFrom:
          secretKeyRef:
            name: permission-service-approle
            key: secret-id
    command: ["/bin/sh","-c"]
    args:
      - |
        vault login -method=approle role_id=$(cat /vault/role-id) secret_id=$VAULT_TOKEN
        vault kv get -field=value secret/${ENV}/permission-service/jwt-signing > /app/secrets/jwt.key
``` 

Main container `/app/secrets/jwt.key` kullanır. Rotasyon job’u yeni secret yazar, pod restart ile yeniler.

## 4. Vault Policy
```hcl
path "secret/data/${ENV}/permission-service/*" {
  capabilities = ["read"]
}

path "secret/data/${ENV}/permission-service/jwt-signing" {
  capabilities = ["read", "update"] # yalnız rotasyon pipeline rolü için
}
```

## 5. Rotasyon Scheduler
- Ops pipeline (GitHub Actions cron) `rotate-service-jwt.sh` + `vault kv patch secret/...` script’lerini tetikler.  
- Slack bildirimleri: rotasyon başarılı/başarısız.  
- Rotasyon sonrası `kubectl rollout restart deployment/permission-service`.

## 6. Checklist
- [ ] CI pipeline secret barındırmıyor; pre-deploy job secret kontrolünü çalıştırıyor.  
- [ ] Rotasyon pipeline script’leri (JWT, DB, third-party) repo içinde.  
- [ ] Init container/sidecar modeli en az bir servis için production’da devrede.  
- [ ] Rotasyon sonrası smoke test (JWT doğrulama, DB bağlantı) otomasyona bağlı.  
- [ ] Slack/alerting rotasyon pipeline’ı izliyor.  
- [ ] Dokümantasyon (Confluence) linklendi.

---
**Sonraki adım:** Hot reload stratejisi (secret cache/runtime refresh).

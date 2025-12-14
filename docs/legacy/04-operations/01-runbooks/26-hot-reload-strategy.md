# Secret Hot Reload Stratejisi

Faz 2 kapsamında secret güncellemesi sonrası servislerin downtime olmadan güncellenmesi için izlenecek yöntem.

## 1. İlkeler
- Vault’tan çekilen data kısa TTL ile cache’lenir (örn. 5 dk).  
- Secret değişince uygulama ya `/actuator/refresh` (Spring Cloud) ya da rolling restart ile güncellenir.  
- Kritik servisler için “read-only degrade” modu belirlenir.

## 2. Spring Boot Servisleri
- Vault config (`spring.cloud.vault.fail-fast=true`, retry).  
- Refresh: `POST /actuator/refresh` (enable limited auth).  
- Rotasyon pipeline: secret güncellenince Slack bildirimi + webhook (Argo Rollout) tetikler.

### Örnek
```bash
curl -X POST -H "Authorization: Bearer $MGMT_TOKEN" https://permission-service.${ENV}.corp/actuator/refresh
```
- Response 200 → log’da `Refreshed 5 beans`.  
- Health check `/actuator/health` → `UP`.

## 3. Rolling Restart Fallback
- Hot reload başarısızsa: `kubectl rollout restart deployment/permission-service`.  
- Blue-green/Canary tercih edilebilir.

## 4. Secret Cache & TTL
- Vault Agent template: `cache { use_auto_auth_token = true default_lease_ttl = "300s" }`.  
- Application cache: maximum TTL = Vault lease TTL - 30s.  
- Monitoring: secret fetch latency > 500ms alarm.

## 5. Checklist
- [ ] `/actuator/refresh` endpoint korumalı (token/MTLS).  
- [ ] Manual refresh script (`scripts/refresh-permission-service.sh`) mevcut.  
- [ ] Rotasyon pipeline refresh hook’u tetikliyor.  
- [ ] Rolling restart fallback runbook’u test edildi.  
- [ ] Monitoring (latency, refresh failure) kuruldu.

---
**Sonraki adım:** Faz 2’de Observability/OPA vb. kalan başlıklara geçiş.

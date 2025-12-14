# Vault Fail-Fast Fallback & Incident Runbook

> Kapsam: Vault kapalıyken (fail-fast true) mikroservislerin nasıl davrandığını, FE’ye hangi hata yüzeyinin gittiğini ve on-call süreçlerini tanımlar.  
> İlgili Story: `SEC-VAULT-FAILOVER-01`.

## 1. Senaryo
- **Tetikleyici:** Vault cluster’ı erişilemez (network hatası, seal, bakım) ve `spring.cloud.vault.fail-fast=true` olan servisler secret çekemediği için start alamıyor.
- **Etkiler:** 
  - Mikroservis pod’ları `CrashLoopBackoff`, `VaultConfigInitializationException`.
  - Gateway health check kırmızı → `/api/**` çağrıları 503 `vault_unavailable`.
  - FE login ekranı maintenance mesajına düşüyor.

## 2. Tespit
1. Grafana `security/vault-clients` panelinde:
   - `vault_client_secret_fetch_duration_seconds` grafiği düz 0 veya timeout spike.
   - `application_start_failed_total{reason="vault"}` metriği artar.
2. Alert: `Security_Vault_Failfast_Restarts` (Alertmanager) → Slack `#security-platform`, PagerDuty “Security Perimeter”.
3. Kibana sorgusu: `service.name:"user-service" AND message:"VaultConfigInitializationException"` (CorrId log).
4. `kubectl get pods -n platform | grep CrashLoopBackOff`.

## 3. Anında Aksiyonlar
1. **Incident Aç:** `INC-<timestamp>` (seviyesi: Major). Incident Commander ve Vault On-Call etiketlenir.
2. **Vault Sağlığını Doğrula:**
   ```bash
   export VAULT_ADDR=https://vault.service.prod:8200
   vault status
   ```
   - `Sealed = true` → auto-unseal / runbook `vault-ha.md`.
   - `connection refused` → network/load balancer kontrolü.
3. **Süreçsel Koruma:**
   - Gateway `MaintenanceGuard` feature flag’i ON (ops cli) → Shell maintenance view tetiklenir.
   - Slack duyurusu (`#status`) “Kimlik altyapısı bakımı” şablonu.
4. **Vault’ı geri getir:**
   - Eğer seal: `vault operator unseal` (bkz. `77-vault-runbook.md`).
   - Eğer komple outage: `vault-restore-drill.md` prosedürü (snapshot restore) devreye alınır.

## 4. Recovery & Doğrulama
1. Vault tekrar `sealed=false`.
2. Servisleri sırasıyla ayağa kaldır:
   ```bash
   kubectl rollout restart deploy auth-service
   kubectl rollout status deploy auth-service --timeout=180s
   ```
   Aynı sıra: auth → user → permission → variant → gateway.
3. Health check:
   ```bash
   curl -f https://gateway.prod/api/health || exit 1
   curl -f -H "Authorization: Bearer <svc-token>" https://gateway.prod/api/v1/users?page=1&pageSize=1
   ```
4. FE doğrulaması:
   - Shell login → beklenen dashboard render.
   - Maintenance view kapanmış mı? (Broadcast to comms).
5. Alert reset:
   - `application_start_failed_total` metriği sabitleniyor mu?
   - `Security_Vault_Failfast_Restarts` alerti resolved mi?

## 5. Post-Incident
- Incident raporu: root cause, süre, RTO/RPO, alınan aksiyonlar.
- `session-log.md` + `PROJECT_FLOW` notu.
- Kaç servis CrashLoopBackoff oldu? SRE backlog’a aksiyon.
- Tam FE/Shell release notu: `docs/03-delivery/api/auth.api.md` “Vault Outage Response” referansı.

## 6. Manuel Test / Drill
> Amaç: Fail-fast davranışını doğrulamak, FE maintenance mesajını gözlemlemek.

1. `docker compose up -d vault keycloak postgres`  
2. `docker compose up -d auth-service user-service`  
3. Vault’ı durdur: `docker compose stop vault`  
4. Servis loglarını izle:  
   `docker compose logs -f auth-service | grep VaultConfigInitializationException`  
5. Gateway’e istek:  
   ```bash
   curl -i -H "X-Trace-Id: drill-001" http://localhost:8080/api/v1/auth/sessions
   # 2025-11-29 16:38 (UTC+3) drill çıktısı
   # HTTP/1.1 503 Service Unavailable
   # Retry-After: 60
   # X-Serban-Outage-Code: VAULT_UNAVAILABLE
   # 
   # {"error":"vault_unavailable","message":"Kimlik altyapısı devrede değil. Bakım tamamlanınca otomatik denenecek.","fieldErrors":[],"meta":{"traceId":"drill-001","outageCode":"VAULT_UNAVAILABLE"}}
   ```
6. FE (Shell dev server) -> login → maintenance banner.
7. Vault tekrar başlat (`docker compose start vault`), `secret/db/*` ve `secret/jwt/auth-service` path’lerine sırları yeniden yaz, auth-service’i prod profilde yeniden ayağa kaldır (`SPRING_PROFILES_ACTIVE=prod ./mvnw -pl auth-service spring-boot:run`).  
8. `curl -f http://localhost:8088/actuator/health` → 200 ve Gateway üzerinden aynı istek 4xx döndürüyorsa (ör. auth gereksinimi) outage kapanmış kabul edilir.

## 7. Referanslar
- `docs/01-architecture/01-system/01-backend-architecture.md#vault-fail-fast-ve-ux`
- `docs/01-architecture/04-security/vault/02-spring-cloud-vault-guide.md`
- `docs/04-operations/02-monitoring/security-dashboard.md`
- Incident şablonu: `docs/04-operations/01-runbooks/69-runbook-updates.md`

---
**Not:** Runbook güncellemeleri için change record açın; Slack `#vault-ops` kanalına “fail-fast drill” duyurusu bırakın.

## 8. Log ve Trace Örneği
Drill sırasında gateway log’larında aşağıdaki satırlar gözlenir:
```
2025-11-29T13:38:31.572Z  WARN 1 --- [api-gateway] [parallel-6]
o.s.c.l.core.RoundRobinLoadBalancer      : No servers available for service: auth-service
2025-11-29T13:38:31.578Z  WARN 1 --- [api-gateway] [parallel-6]
c.e.a.f.VaultFailfastFallbackHandler     : Vault fail-fast fallback devreye alındı:
503 SERVICE_UNAVAILABLE "Unable to find instance for auth-service"
```
- Log mesajı yalnızca outage bilgisini içerir; secret/token değerleri yazılmaz.
- Aynı traceId `meta.traceId` içinde API yanıtına da taşınır, böylece FE/Gateway logları korele edilir.
- Drill tamamlandığında servislerin normale döndüğünü doğrulamak için `curl -f http://localhost:8080/api/health`, `curl -f http://localhost:8088/actuator/health` ve gateway üzerinden yapılan bir istek (ör. `curl -i -H "X-Trace-Id: drill-002" http://localhost:8080/api/v1/auth/sessions`) 4xx/2xx döndüğünde sonuçlar session-log’a işlenir.

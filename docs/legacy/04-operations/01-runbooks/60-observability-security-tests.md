# Observability & Security Test Otomasyonu Planı

Faz 3 kapsamında Vault entegrasyonuna paralel olarak log standardı, monitoring ve negatif senaryo testlerini kapsayan plan.

## 1. Log Standardı
- JSON format: `timestamp`, `level`, `service`, `corrId`, `event`, `details`, `kid` (JWT), `vaultOp`.  
- Spring Boot için `logback-spring.xml` JSON encoder; Node/TS için Winston JSON.  
- Correlation ID (HTTP header `X-Correlation-Id`) inbound→outbound propagate.

## 2. Vault Audit Merkezi Toplama
- Syslog → SIEM pipeline (Splunk/ELK).  
- Parsley pipeline: `vault.audit.*` index, fields `auth.actor`, `request.path`, `error`.  
- Retention: 90 gün.

## 3. Dashboard & Alerts
- Grafana:  
  - Panel: `jwt_validation_failure_total`, `vault_client_secret_fetch_duration_seconds`, `opa_decision_denied_total`.  
  - Logs: `log_type=vault` vs `log_type=app`.  
- Alert:  
  - `jwt_validation_failure_total` spike >5/min.  
  - `vault.seal` = 1.  
  - `transit` error rate >1%.  
  - `db-creds` lease expiring <15 min.

## 4. Security Test Otomasyonu
- Test suite:  
  - Unauthorized (no token) → 401.  
  - Expired token → 401.  
  - Claim mismatch (`env`, `perm`) → 403.  
  - mTLS missing (OPA policy, TLS handshake fail).  
  - JWKS unreachable fallback → degrade mode.  
  - Vault offline → fail-fast.
- Tools: Postman/Newman, k6, playwright API tests.
- CI stage `security-tests` (staging environment): nightly run + PR optional.

## 5. Checklist
- [ ] JSON log format tüm servislerde devreye alındı.  
- [ ] Correlation ID propagation onaylandı (gateway → servis → audit).  
- [ ] Vault audit logları merkezi SIEM’de, panolar güncel.  
- [ ] Dashboard & alerts konfigüre edildi (Grafana/Alertmanager).  
- [ ] Security test suite CI pipeline’a eklendi.  
- [ ] Raporlama (weekly) ve Confluence linkleri oluşturuldu.

---
**Sonraki adım:** Runbook güncellemeleri (unseal/restore/break-glass, secret/cert rotasyonu) ve Faz 3 kill-switch tamamlaması.

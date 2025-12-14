# Vault HA & Auto-Unseal Deployment

Faz 2 kapsamında Vault’u çok düğümlü (Raft) ve auto-unseal destekli hale getirmek için gereken mimari, konfigürasyon ve operasyon adımları.

## 1. Topoloji
- 3× Vault node (minimum) + load balancer:
  - `vault-01.{env}.corp`
  - `vault-02.{env}.corp`
  - `vault-03.{env}.corp`
- Load balancer: `vault.service.{env}.corp:8200` (TCP passthrough, health check `/v1/sys/health`).
- Storage: Integrated Raft, data path `/opt/vault/data` (separate disk/LVM önerilir).
- Auto-unseal: Cloud KMS (örn. AWS KMS) veya HSM (örn. Thales Luna), key ring `vault-auto-unseal-{env}`.

### Ağ & Güvenlik
- Vault nodeları birbirini 8201 portu üzerinden görebilmeli.
- LB → Vault trafik TLS (mutual TLS opsiyonel).
- Admin erişimi yalnız `vault-admins` security group / jump host üzerinden.

## 2. Raft Konfigürasyonu
`/etc/vault.d/vault.hcl` (her node için `node_id` farklı, aynı cluster name):
```hcl
cluster_name = "vault-ha"
ui = true

storage "raft" {
  path    = "/opt/vault/data"
  node_id = "vault-01"
}

listener "tcp" {
  address       = "0.0.0.0:8200"
  cluster_address = "vault-01.{env}.corp:8201"
  tls_cert_file = "/etc/vault.d/tls/vault.crt"
  tls_key_file  = "/etc/vault.d/tls/vault.key"
  tls_client_ca_file = "/etc/vault.d/tls/ca.crt"
}

seal "awskms" {
  region     = "${AWS_REGION}"
  kms_key_id = "${AUTO_UNSEAL_KEY_ID}"
}
``` 

- İlk node’i `vault operator init` (key shares 5-of-3) ile başlatın.
- Diğer nodelar için: `vault operator raft join https://vault-01.{env}.corp:8200`.
- `vault operator raft list-peers` ile cluster health kontrol.

## 3. Auto-Unseal
- KMS arn: `arn:aws:kms:REGION:ACCOUNT:key/UUID` → `AUTO_UNSEAL_KEY_ID` env olarak geçilir.
- IAM role `vault-server-{env}` için KMS `Encrypt/Decrypt/GenerateDataKey` izinleri.
- Cold start: `/etc/systemd/system/vault.service` içinde `EnvironmentFile=/etc/vault.d/vault.env` → `VAULT_AWSKMS_SEAL_KEY_ID=...`.
- Failover testi: Vault servisini tamamen durdurup geri başlatın, auto-unseal çalışmalı (`vault status` sealed=false).

## 4. Runbook
### 4.1 Node Ekleme
1. Yeni VM/Pod provisioning (aynı AMI/OS image).  
2. TLS sertifikası çıkar ve `/etc/vault.d/tls/` altına yerleştir.  
3. `vault.d` konfigini kopyala, `node_id` ve `cluster_address` güncelle.  
4. `systemctl start vault`.  
5. `vault operator raft join https://vault.service.{env}.corp:8200`.  
6. `vault operator raft list-peers` ile confirm.

### 4.2 Node Çıkarma
1. `vault operator raft remove-peer -address=node-address:8201`.  
2. Servisi durdur, data diski wipe et.  
3. Load balancer health check’ten çıkar.  
4. CMDB/asset kaydı güncelle.

### 4.3 Split-Brain Çözümü
- Raft quorum kaybı → `vault operator raft autopilot state`.  
- Gerekirse `vault operator raft remove-peer` ile hatalı node drop.  
- `vault operator raft snapshot save` → restore.

## 5. Monitoring & Alerts
- Metrikler: `vault.autopilot.*`, `vault.raft.apply`, `vault.seal` (Prometheus exporter).  
- Alarmlar: `vault.seal` = 1, `vault.autopilot.health_status != healthy`, Raft peers mismatch.  
- Logs: syslog’ta `unseal` ve `seal` eventleri, SIEM’de dashboard.

## 6. Checklist
- [ ] 3 node raft cluster production’da çalışıyor, load balancer health check’leri yeşil.  
- [ ] Auto-unseal KMS key’i devreye alındı; manual unseal gerekmiyor.  
- [ ] Node ekleme/çıkarma runbook’u test edildi.  
- [ ] Split-brain/snapshot restore tatbikatı planlandı.  
- [ ] Monitoring ve alarmlar (Prometheus/Alertmanager) güncellendi.

---
**Sonraki adım:** Snapshot restore tatbikatı dokümantasyonu (`restore-report.md` taslağı).

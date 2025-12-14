# Vault Single-Node Vault Baseline

Baseline configuration for the initial single-node Vault deployment with integrated Raft storage, TLS, dual audit sinks, and daily snapshot plus offsite copy procedures.

## Topology & Scope
- **Deployment model:** 1× Vault server, integrated Raft storage, running under `vault` systemd service.
- **Environment:** Pilot in staging, promoted to production after acceptance (Faz 1 scope).
- **Reference hosts:** `vault-01.{env}.corp` (adjust per environment).
- **Configuration location:** `/etc/vault.d/vault.hcl`, supporting scripts in `/opt/vault/bin`.

## TLS Configuration

### Certificate Requirements
- Issue Vault server certificates from the internal PKI (or interim intermediate CA) with SANs for `vault-01.{env}.corp` and `vault.service.{env}.corp`.
- Minimum key size RSA 4096 or EC P-256; validity ≤12 months.
- Store `/etc/vault.d/tls/vault.crt`, `/etc/vault.d/tls/vault.key`, `/etc/vault.d/tls/ca.crt` with permissions `0640` owned by `vault:vault`.
- Maintain certificate inventory in `security-cert-tracker.xlsx`; schedule rotation reminder 30 days before expiry.

### Configuration Snippet (`/etc/vault.d/vault.hcl`)
```hcl
storage "raft" {
  path    = "/opt/vault/data"
  node_id = "vault-01"
}

listener "tcp" {
  address                          = "0.0.0.0:8200"
  cluster_address                  = "vault-01.{env}.corp:8201"
  tls_cert_file                    = "/etc/vault.d/tls/vault.crt"
  tls_key_file                     = "/etc/vault.d/tls/vault.key"
  tls_client_ca_file               = "/etc/vault.d/tls/ca.crt"
  tls_disable                      = 0
  tls_require_and_verify_client_cert = "false" # revisit once mTLS client auth is in place
}

api_addr     = "https://vault-01.{env}.corp:8200"
cluster_addr = "https://vault-01.{env}.corp:8201"
disable_mlock = false
log_level     = "info"
ui            = true
```

### Operational Steps
1. Provision directories: `sudo install -d -o vault -g vault /etc/vault.d/tls /opt/vault/data`.
2. Copy cert/key files, set ownership `sudo chown vault:vault` and permissions `chmod 0640`.
3. Validate certificate chain with: `openssl verify -CAfile /etc/vault.d/tls/ca.crt /etc/vault.d/tls/vault.crt`.
4. Restart Vault service: `sudo systemctl restart vault`; confirm logs show TLS listener active.
5. Post-start check: `VAULT_ADDR=https://vault-01.{env}.corp:8200 vault status`.

## Audit Logging

### Requirements
- Enable both file and remote syslog audit devices.
- Retain file audit logs for ≥90 days locally with rotation (daily rotation, 4 GB max per file).
- Forward syslog to central SIEM (`syslog.siem.corp:6514` over TCP with TLS).

### Configuration Snippet
```hcl
audit "file" {
  path = "/var/log/vault/audit.log"
  mode = "0640"
}

audit "syslog" {
  address  = "tcp+tls://syslog.siem.corp:6514"
  facility = "LOCAL4"
  tag      = "vault"
}
```

### Operational Steps
1. Enable devices (once Vault is unsealed):
   ```bash
   vault audit enable file file_path=/var/log/vault/audit.log log_raw=true
   vault audit enable syslog address="tcp+tls://syslog.siem.corp:6514" facility="LOCAL4" log_raw=true
   ```
2. Ensure `/var/log/vault` exists with `vault:security` ownership and permissions `0750`.
3. Add logrotate policy `/etc/logrotate.d/vault-audit`:
   ```
   /var/log/vault/audit.log {
       daily
       rotate 30
       compress
       missingok
       copytruncate
   }
   ```
4. Observe sample entry: trigger `vault token create -policy=default` and verify both local file and SIEM events.

## Snapshot & Offsite Copy Plan

### Objectives
- **RPO:** ≤24 h (daily snapshot minimum).
- **RTO:** ≤2 h for single-node restore.
- Encrypt snapshots in transit and at rest; store locally and in offsite object storage (e.g., S3 bucket `s3://vault-backups-{env}`).

### Snapshot Script (`/opt/vault/bin/backup.sh`)
```bash
#!/usr/bin/env bash
set -euo pipefail

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
SNAP_DIR="/var/backups/vault"
SNAP_FILE="${SNAP_DIR}/vault-${STAMP}.snap"

export VAULT_ADDR="https://vault-01.${ENV}.corp:8200"
export VAULT_TOKEN="$(cat /etc/vault.d/snapshot-token)"

mkdir -p "${SNAP_DIR}"
vault operator snapshot save "${SNAP_FILE}"

aws s3 cp "${SNAP_FILE}" "s3://vault-backups-${ENV}/daily/${STAMP}.snap" --sse AES256
find "${SNAP_DIR}" -type f -name "vault-*.snap" -mtime +7 -delete
```

### Scheduling
- Create read-only snapshot token scoped to `sys/storage/raft/snapshot`.
- Store token at `/etc/vault.d/snapshot-token` with `0600` permissions.
- Install script with ownership `vault:vault`, mode `0750`.
- Schedule via systemd timer:
  - `/etc/systemd/system/vault-backup.service` executes the script.
  - `/etc/systemd/system/vault-backup.timer` runs daily at `01:30` UTC.
  - Enable timer: `sudo systemctl enable --now vault-backup.timer`.

### Verification & Offsite Audit
- Monthly: perform restore drill in isolated environment, document in `restore-report.md`.
- Weekly: verify S3 object existence and integrity (`aws s3 ls`, `vault operator snapshot inspect`).
- Alerting: configure CloudWatch (or equivalent) to alarm on missing snapshot file for >26 h.

## Acceptance Checklist
- [ ] TLS certificates installed, Vault listener reachable only over HTTPS, status command succeeds.
- [ ] File audit log captures sample entry, logrotate functioning.
- [ ] Syslog stream visible in SIEM with correct facility/tag.
- [ ] Daily snapshot job produces local file and offsite copy; retention policy enforced.
- [ ] Restore procedure test scheduled and documented.

---  
**Owners:** Platform Engineering (Vault ops), Security Engineering (PKI & audit oversight).  
**Next Step:** Proceed to `vault-runbook.md` per Faz 1 roadmap.

# Vault PKI & mTLS Stratejisi

Faz 2 kapsamında microservice iletişimini mutual TLS ile güvence altına almak ve sertifika yaşam döngüsünü Vault PKI üzerinde yönetmek için gereklilikler.

## 1. Mimari
- Vault PKI backend: 
  - Root CA (offline) → Intermediate CA (Vault) → Service certs.
  - Path önerileri:
    - `pki/root` (offline, biletiyle yönetim)
    - `pki/intermediate` (Vault aktif)
    - `pki/roles/<service>` (mTLS sertifikaları)
- Sertifika zinciri Vault tarafından imzalanacak, servisler TLS termination’ı K8s sidecar veya ingress üzerinde gerçekleştirecek.

## 2. PKI Backend Kurulumu
1. Root CA offline makinede (Hashicorp Vault veya CFSSL) üretilir, sadece intermediate’ı imzalar.
2. Vault’ta intermediate mount:
   ```bash
   vault secrets enable -path=pki/int pki
   vault secrets tune -max-lease-ttl=8760h pki/int
   vault write -field=csr pki/int/intermediate/generate/internal common_name="${ENV} Intermediate CA" > int.csr
   # Root CA ile imzala, sonucu import et
   vault write pki/int/intermediate/set-signed certificate=@signed.pem
   vault write pki/int/config/urls issuing_certificates="https://vault.service.${ENV}.corp/v1/pki/int/ca" crl_distribution_points="https://vault.service.${ENV}.corp/v1/pki/int/crl"
   ```
3. Role tanımı:
   ```bash
   vault write pki/int/roles/permission-service allow_any_name=false allow_subdomains=true allowed_domains="permission-service.svc.${ENV}.corp" max_ttl="720h"
   ```

## 3. Sertifika Yaşam Döngüsü
- Default TTL: 10 gün (240h), max TTL: 30 gün (720h).  
- Renew: servis sidecar’ı (Vault Agent veya cert-manager) otomatik yenileme yapacak.  
- Revocation: `vault write pki/int/revoke serial_number=...` + CRL publish (`vault write pki/int/tidy`).  
- CRL/OCSP: Vault CRL endpoint’i; isteğe bağlı OCSP responder.

## 4. Servis Entegrasyonu
### 4.1 Vault Agent Sidecar
- Deployment manifest’ine Vault Agent sidecar ekle:
  ```yaml
  env:
    - name: VAULT_ADDR
      value: https://vault.service.${ENV}.corp:8200
  volumeMounts:
    - mountPath: /vault/secrets
      name: vault-secrets
  templates:
    - destination: /vault/secrets/tls.pem
      contents: |
        {{ with secret "pki/int/issue/permission-service" "common_name=permission-service.svc.${ENV}.corp" }}
        {{ .Data.certificate }}
        {{ end }}
  ```
- Application pod mTLS için `/vault/secrets/tls.pem` + key dosyasını kullanır.

### 4.2 cert-manager (K8s)
- Issuer tanımı:
  ```yaml
  apiVersion: cert-manager.io/v1
  kind: ClusterIssuer
  metadata:
    name: vault-intermediate-issuer
  spec:
    vault:
      server: https://vault.service.${ENV}.corp:8200
      path: pki/int/sign/permission-service
      auth:
        kubernetes:
          mountPath: auth/kubernetes
          role: permission-service
  ```

## 5. Runbook
- Sertifika talebi: `vault write pki/int/issue/permission-service common_name=permission-service.svc.${ENV}.corp ttl=240h`
- Sertifika yenileme: sidecar otomatik; manuel test için aynı komut.
- Sertifika iptali: `vault write pki/int/revoke serial_number=<serial>` → CRL update.
- CRL tidy: haftalık cron `vault write pki/int/tidy safety_buffer=72h`.

## 6. Checklist
- [ ] Root CA offline üretildi ve güvenli şekilde saklanıyor.
- [ ] Vault intermediate CA devreye alındı, URL’ler yayınlandı.
- [ ] Sertifika role’leri (service başına) tanımlandı.
- [ ] En az bir servis için Vault Agent / cert-manager ile mTLS sertifikası dağıtıldı.
- [ ] Monitoring/alert: sertifika TTL <24h olduğunda uyarı.
- [ ] Runbook Confluence’da referanslandı.

---
**Sonraki adım:** DB Secrets Engine (PostgreSQL) dokümantasyonu.

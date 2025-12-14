# Vault Transit Engine Entegrasyonu

Faz 3 kapsamında uygulama düzeyinde şifreleme/deşifre işlemlerini Vault Transit Engine’e devretme.

## 1. Kullanım Senaryoları
- PII / müşteri verisi kolon şifreleme.  
- Doğrulama kodu, token gibi kısa-lived secret’ların HMAC doğrulaması.  
- Uygulamalar: `notification-service`, `user-service` (email/phone), `payment-service` (kart token).

## 2. Konfigürasyon
1. Transit enable:
   ```bash
   vault secrets enable transit
   vault write -f transit/keys/customer-data type="aes256-gcm96"
   vault write -f transit/keys/token-hmac type="aes256-gcm96" convergent_encryption=true derived=true
   ```
2. Policy örneği:
   ```hcl
   path "transit/encrypt/customer-data" {
     capabilities = ["update"]
   }
   path "transit/decrypt/customer-data" {
     capabilities = ["update"]
   }
   ```
3. Rotasyon: `vault write -f transit/keys/customer-data/rotate`.

## 3. Uygulama Entegrasyonu
- REST API:
  ```bash
  vault write transit/encrypt/customer-data plaintext=$(base64 <<< "secret")
  vault write transit/decrypt/customer-data ciphertext="vault:v1:..."
  ```
- SDK: Spring Cloud Vault `VaultTemplate#opsForTransit()`.  
- Wrapper library yazıp servislerde DI ile kullan.

## 4. Audit & Monitoring
- Audit log: `transit/encrypt`, `transit/decrypt` entry’leri.  
- Metrics: `vault.transit.encrypt.count`, `vault.transit.decrypt.count`.  
- Alarm: `transit` error rate > 1%.

## 5. Checklist
- [ ] Transit engine enable edildi (dev/staging).  
- [ ] Anahtarlar (customer-data, token-hmac vb.) oluşturuldu; rotasyon planı belirlendi.  
- [ ] En az bir uygulama Transit ile şifreleme/deşifre yapıyor.  
- [ ] Audit log ve monitoring kuralları hazır.  
- [ ] Dokumentasyon (Confluence) güncellendi.

---
**Sonraki adım:** Observability (log JSON + correlation, Vault audit centralization) ve security test otomasyonu.

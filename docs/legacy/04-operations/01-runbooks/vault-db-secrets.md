# Vault DB Secrets Engine – PostgreSQL

Faz 2 kapsamında PostgreSQL veritabanı kimlik bilgilerinin dinamik olarak yönetilmesi ve TTL bazlı otomatik rotasyonun uygulama entegrasyonu.

## 1. Gereksinimler
- Dinamik kullanıcı yaratımı (role-based).  
- TTL politikaları:  
  - KV: ≤ 90 gün  
  - Sertifika: 24-48 saat  
  - DB kullanıcıları: 1–8 saat (default 4h).  
- Alarm: Kullanıcı TTL < 15 dk olduğunda uyarı.
- Uygulamalar bağlantı pool’larını yenileyebilmeli (Vault Agent template veya init container).

## 2. Vault Konfigürasyonu
1. Secrets engine enable:
   ```bash
   vault secrets enable -path=db postgres
   vault write db/config/app-db \
     plugin_name=postgresql-database-plugin \
     allowed_roles="read-only,read-write" \
     connection_url="postgresql://{{username}}:{{password}}@db.${ENV}.corp:5432/app_db?sslmode=require" \
     username="$VAULT_DB_ADMIN_USER" \
     password="$VAULT_DB_ADMIN_PASSWORD"
   ```
2. Role tanımları:
   ```bash
   vault write db/roles/read-only \
     db_name=app-db \
     creation_statements="CREATE ROLE \"{{name}}\" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}'; GRANT SELECT ON ALL TABLES IN SCHEMA public TO \"{{name}}\";" \
     default_ttl="2h" \
     max_ttl="8h"

   vault write db/roles/read-write \
     db_name=app-db \
     creation_statements="CREATE ROLE \"{{name}}\" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}'; GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO \"{{name}}\";" \
     default_ttl="4h" \
     max_ttl="8h"
   ```
3. Kullanım:
   ```bash
   vault read db/creds/read-only
   ````

## 3. Uygulama Entegrasyonu
### 3.1 Vault Agent Template
- Deployment init container `vault login` → template file `/vault/secrets/db-creds.json`.
- Spring Boot örneği: `spring.datasource.url=...`, `username` ve `password` startup sırasında template’ten okunur.
- Rotasyon: Vault Agent lease renew veya secret renewal hook → uygulama bağlantı pool’u refresh.

### 3.2 Sidecar/Init Container
- Init container secret alır, main container environment’a inject eder.  
- Lease TTL sona ermeden application bir `POST /actuator/refresh` tetikleyebilir veya rolling restart.

## 4. Veritabanı Ayarları
- PostgreSQL side: `ALTER ROLE <template_user> WITH CREATEDB CREATEROLE` (template user).  
- `search_path=public` ve default schema grant’leri.  
- `pg_hba.conf` Vault server IP’lerine TLS ile izin verir.

## 5. Monitoring & Alerting
- Vault metric: `vault.database.creds.lease_duration` → TTL <15 dk alarm.  
- DB audit log: dyn kullanıcı login/logout entry’leri.  
- Grafana panel: aktif lease sayısı, issuance rate.

## 6. Checklist
- [ ] Vault postgres secret engine enable edildi ve connection test başarılı.  
- [ ] Read-only / read-write role’leri tanımlandı.  
- [ ] Uygulama tarafında dinamik credential entegrasyonu (örnek servis) çalışıyor.  
- [ ] TTL rotasyon testi yapıldı, uygulama downtime olmadan credential yeniledi.  
- [ ] Monitoring/alert kuralları (TTL, issuance failure) devrede.  
- [ ] Dokümantasyon Confluence’a linklendi.

---
**Sonraki adım:** JWT rotasyon planı (Faz 2).

# Vault Snapshot Restore Tatbikatı

Faz 2 kapsamında günlük snapshot planının doğrulanması ve RPO ≤15dk / RTO ≤30dk hedeflerinin test edilmesi.

## 1. Senaryo & Hedefler
- **Senaryo:** Üretim Vault cluster’ında kalıcı veri kaybı (örn. disk arızası).  
- **RPO hedefi:** ≤15 dakika (son snapshot + delta).  
- **RTO hedefi:** ≤30 dakika (backup’tan yeni node ayağa kaldırma).  
- **Çıktı:** Tatbikat raporu (`restore-report-YYYYMMDD.md`), Prometheus/Alert log ekran görüntüleri, checklist güncellemesi.

## 2. Tatbikat Ortamı
- İzole staging veya DR ortamı (prod’dan ayrık).  
- Vault binary aynı sürüm (`vault version`).  
- Vault snapshot dosyası: S3 `s3://vault-backups-{env}/daily/<timestamp>.snap` veya lokal backup.  
- IAM yetkisi: snapshot bucket read, KMS decrypt (auto-unseal varsa).  
- DNS/LB: staging için `vault-drill.{env}.corp`.

## 3. Adım Adım Restore
1. **Hazırlık**  
   - Tatbikat JIRA ticket’ı aç (Tarih, katılımcılar, RPO/RTO hedefleri).  
   - Son snapshot timestamp’ini belirle (`aws s3 ls`).  
   - Audit log’tan snapshot job’un başarılı olduğunu doğrula.
2. **Yeni Node Provisioning**  
   - `vault-drill-01` VM/Pod oluştur, TLS sertifikası yükle.  
   - `/etc/vault.d/vault.hcl` içinde storage türü `raft` veya dev restore için `file`.  
   - Auto-unseal gerekiyorsa KMS key env değişkenlerini set et.
3. **Restore**  
   ```bash
   export VAULT_ADDR=http://127.0.0.1:8200
   vault operator init -key-shares=1 -key-threshold=1 > /tmp/init.txt
   vault operator unseal $(grep 'Unseal Key 1' /tmp/init.txt | awk '{print $NF}')
   vault operator raft snapshot restore -force /path/to/snapshot.snap
   ```
   - Restore sonrası unseal otomatiktir (raft snapshot state).  
   - `vault status` → `Sealed: false`, `Cluster ID` doğrulanır.
4. **Validation**  
   - `vault kv get secret/{env}/sample` (kritik secret) sonucu beklenen değer mi?  
   - AppRole token / JWT signing key gibi kritik path’ler test edilir.  
   - Audit log’da restore event kaydı gözlemlenir.
5. **RTO/RPO Ölçümü**  
   - T0: incident announce zamanı.  
   - T1: snapshot restore start.  
   - T2: restore tamamlandı (`vault status`).  
   - RTO = T2 - T0, RPO = current time - snapshot timestamp.  
   - Hedefler tutmuyorsa aksiyon planı çıkar.
6. **Temizlik**  
   - Tatbikat ortamını kapat.  
   - Snapshot dosyası ve unseal key çıktılarını imha et.  
   - JIRA ticket’ı tatbikat raporu ve loglarla güncelle.

## 4. Rapor Şablonu (`restore-report-YYYYMMDD.md`)
```markdown
# Vault Restore Tatbikatı – YYYY-MM-DD

## Katılımcılar
- Platform On-Call:
- Security Representative:
- Observer:

## Snapshot Detayı
- Kaynak: s3://vault-backups-prod/daily/20250101T010000Z.snap
- Snapshot Timestamp: 2025-01-01 01:00 UTC

## Zaman Çizelgesi
- T0 Incident Declare: 2025-01-02 12:03 UTC
- T1 Restore Start: 2025-01-02 12:08 UTC
- T2 Restore Complete: 2025-01-02 12:26 UTC
- RPO: 8 dk
- RTO: 23 dk

## Doğrulama
- [x] vault kv get secret/prod/payment-service/jwt-signing
- [x] AppRole login testi
- [x] Audit log entry

## Notlar & İyileştirmeler
- ...

## Onaylar
- Platform Lead – __
- Security Architect – __
```

## 5. Checklist Güncellemesi
- [ ] Tatbikat planlandı, JIRA referansı: `VAULT-DR-XXXX`.  
- [ ] Restore başarıyla tamamlandı, RPO/RTO hedefleri tutturuldu.  
- [ ] `restore-report-YYYYMMDD.md` dokümanı `docs/` altına eklendi ve Confluence’a linklendi.  
- [ ] Takım öğrenimleri ve aksiyonlar backlog’a eklendi.

---
**Sonraki adım:** Vault PKI & mTLS dokümantasyonu.

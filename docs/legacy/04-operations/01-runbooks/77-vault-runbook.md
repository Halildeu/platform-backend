# Vault Rekey & Unseal Runbook

3-of-5 shard rekey, unseal ve root token yönetimine ilişkin operasyon adımları. Faz 1 kapsamında hazırlanan kasalama envanteri ve sorumluluk matrisini içerir.

## 1. Roller & İletişim
- **Runbook sahipleri:** Platform Engineering (operasyon), Security Engineering (gözetim).
- **İletişim kanalı:** `#vault-ops` Slack kanalı, kritik durumlarda On-Call telefon zinciri.
- **RACI özeti:**
  - **Responsible:** Vault On-Call (Platform)
  - **Accountable:** Platform Lead
  - **Consulted:** Security Architect, Compliance Officer
  - **Informed:** Incident Commander, Uygulama Takım Temsilcileri

## 2. Kasalama Envanteri
| Shard | Sahip | Saklama Yeri | Erişim Prosedürü | DR Notu |
|-------|-------|--------------|------------------|---------|
| Shard A | Platform Lead | Şirket Kasası #1 | İki imza + günlük erişim defteri | DR sahasında kopyası yok |
| Shard B | Security Architect | Şirket Kasası #2 | Güvenlik departmanı nöbetçi refakati | Haftalık kontrol |
| Shard C | DevOps Müh. #1 | Banka kasası (bölge şubesi) | 24 s ön bildirim, kimlik doğrulama | Şube DR planında |
| Shard D | CTO Asistanı | Noter emanet kasası | Resmi dilekçe + kimlik | Yıllık doğrulama |
| Shard E | Incident Commander | Dijital kasa (HSM tabanlı) | MFA + onaylı cihaz | 12 saatlik audit log tutulur |

> Not: Shard dağılımı örnek niteliğindedir; gerçek sahiplik bilgilerini `vault-shard-inventory.xlsx` dosyasında güncelleyin.

## 3. Hazırlık Kontrolleri
1. Shard sahipleri yılda iki kez erişim testini imza altına alır.
2. Shard zarfları tamper-evident mühürlüdür; son kontrol tarihi `vault-shard-inventory.xlsx` içinde kayıtlı olmalıdır.
3. Runbook baskısı güvenli dolapta saklanır; dijital kopya `Vault` isimli Confluence alanında erişilebilir.
4. On-Call listesi güncel değilse rekey/unseal operasyonuna başlanmaz.

## 4. Rekey Prosedürü (3-of-5)

### 4.1 Ön Koşullar
- Rekey kararı Platform Lead + Security Architect onayı ile alınır.
- Rekey hedefi: 5 shard, gerekli eşik 3 shard.
- Vault aktif ve unsealed durumda olmalı.
- Düşük trafikli zaman dilimi seçilmeli (örn. gece 01:00 UTC).

### 4.2 Adımlar
1. **Hazırlık:** `vault operator rekey -init -key-shares=5 -key-threshold=3` komutunu çalıştır; çıktıdaki `nonce` ve `operation` ID’yi kaydet.
2. **Shard toplama:** İlk üç shard sahibi güvenli kanalda bilgilendirilir; kasalama prosedürleriyle shard’larını çıkarır.
3. **Giriş:** Her shard sahibi sırasıyla `vault operator rekey -nonce=<nonce>` komutuna shard değerini girer.
4. **Tamamlama:** Üçüncü shard girildiğinde Vault yeni unseal key set’ini verir. Yeni shard’lar hemen zarflanır ve sahiplerine teslim edilir.
5. **Kayıt:** `vault operator rekey -status` ile doğrula; eski shard’lar imha edilir (parçalayıcı + tanık imzası).
6. **Dokümantasyon:** `vault-rekey-log-YYYYMMDD.md` dokümanına tarih, katılan kişiler, saklama yerleri işlenir; Security depoya yükler.

### 4.3 Failover Planı
- Rekey sırasında hata alınırsa `vault operator rekey -cancel` komutu ile mevcut state sıfırlanır.
- Rekey başarısızlıkları `INC-<id>` incident kaydı olarak açılır.

## 5. Unseal Prosedürü

### 5.1 Tetikleyiciler
- Vault restart, upgrade sonrası yeniden başlatma.
- Disaster recovery (ana node kaybı).
- Acil durum (tamper detection, compromise şüphesi).

### 5.2 Adımlar
1. Vault sunucusunun durumu kontrol edilir: `vault status`.
2. Shard sahiplerine çağrı zinciri ile haber verilir (minimum üç kişi).
3. Her sahip `vault operator unseal` komutu ile shard’ını girer; 3 shard sonrası Vault unseal olur.
4. `vault status` ile `sealed = false` doğrulanır.
5. Unseal sonrası servislerin health check’leri (gateway, kritik servisler) gözlemlenir.
6. Olay kaydı `vault-unseal-log-YYYYMMDD.md` içine işlenir.

### 5.3 Güvenlik Notları
- Shard iletimleri fiziksel ortamda yapılır; dijital aktarım yasaktır.
- Shard girildikten sonra terminal logları temizlenir, history silinir.
- Incident Commander olay süresince iletişimi koordine eder.

## 6. Root Token Yönetimi
1. Rekey sonrasında root token revoke edilir; yalnızca break-glass senaryosu için `vault operator generate-root` yöntemi kullanılır.
2. Root token talep eden kişi Incident Commander onayı olmadan süreci başlatamaz.
3. `generate-root` shard eşikleri 3-of-5 aynı sahiplik modeliyle çalışır.
4. Root token kullanımı işlem tamamlandıktan sonra `vault token revoke` ile iptal edilir; log kaydı tutulur.

## 7. Periyodik Bakım
- Her çeyrekte kasalama envanteri güncellenir.
- Rekey tatbikatı yılda bir defa test ortamında yapılır, raporu `vault-runbook-attestations/` klasörüne eklenir.
- Unseal tatbikatı her altı ayda bir planlı bakım sırasında uygulanır.

## 8. Ekler
- `vault-shard-inventory.xlsx`: Güncel shard sahiplik ve kontrol tarihleri.
- `vault-call-tree.pdf`: On-Call iletişim zinciri.
- `vault-rekey-log-*.md`: Tarihçeler.
- `vault-unseal-log-*.md`: Unseal olay kayıtları.

---
**Sonraki Adım:** Secret path standardı ve kategori taksonomisi (`secret-standards.md`) hazırlığına geçin.

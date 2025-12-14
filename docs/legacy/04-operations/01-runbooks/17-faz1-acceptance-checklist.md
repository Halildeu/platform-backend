# Faz 1 Kabul & Smoke Test Checklist

Vault hızlı güvence fazının tamamlandığını doğrulamak için kullanılacak checklist. Kod ve dokümantasyon hazırlıkları tamamlandı; aşağıdaki pratik doğrulamalar (Vault smoke testleri, staging rotasyon tatbikatı, observability kontrolleri ve imzalar) gerçekleştirildikten sonra Faz 2’ye geçilebilir.

## 1. Doküman Doğrulaması
- [ ] `docs/vault-config.md` gözden geçirildi, ortam değerleri (`{env}` vb.) dolduruldu.
- [ ] `docs/vault-runbook.md` shard sahiplik bilgileri ve iletişim kanalları ile güncellendi.
- [ ] `docs/secret-standards.md` kategori tablosu ve path örnekleri servis ihtiyaçlarına göre revize edildi.
- [ ] `policy-templates/` HCL dosyaları gerçek servis isimleriyle çoğaltıldı; README talimatları uygulandı.
- [ ] `docs/client-credentials-jwt.md` ve `docs/gateway-jwt-failover.md` mimari karar kaydına (ADR) linklendi.
- [ ] `docs/spring-cloud-vault-guide.md` ve `docs/kill-switch-plan.md` operasyon runbook’una referanslandı.

## 2. Yapılandırma & Altyapı Smoke Testleri
- [ ] Vault single-node ortamı TLS + audit log ile ayağa kaldırıldı; `vault status` başarılı.
- [ ] Audit loglarda hem file hem syslog kayıtları görüldü; `logrotate` testi yapıldı.
- [ ] Snapshot script’i (`/opt/vault/bin/backup.sh`) test edildi; lokal ve S3 kopyası doğrulandı.
- [ ] Rekey tatbikatı simüle edildi (`vault operator rekey -init` dry-run).
- [ ] Unseal prosedürü tatbikatı yapıldı; `vault-unseal-log-YYYYMMDD.md` oluşturuldu.
- [ ] Secret path standardına göre en az bir servis secret’ı (`secret/staging/...`) oluşturuldu ve audit logda görüntülendi.

## 3. Uygulama Entegrasyon Testleri
- [ ] Spring Cloud Vault `fail-fast` konfigi ile uygulama başlatıldı; secret eksikliğinde start-up başarısız oldu (alert üretildi).
- [ ] Vault AppRole/K8s policy şablonları gerçek servisler için uygulandı; token erişim testleri (`vault login`) başarıyla geçti.
- [ ] Client credentials JWT akışıyla token alındı; Gateway/servis doğrulaması `200` döndü.
  - (Mint by Auth) `POST /oauth2/token` → 200; `invalid_client` (401), `invalid_audience`/`invalid_permission` (400), limit aşımı (429) senaryoları test edildi.
- [ ] Expired/claim mismatch/mTLS yok senaryoları otomasyon testlerine eklendi ve fail ediyor.
- [ ] JWKS KID rotasyonu staging ortamında test edildi (staging → active → deprecated).
- [ ] Auth-service `/oauth2/jwks` uç noktası erişilebilir ve RS256 anahtarı yayınlıyor.
- [ ] `scripts/rotate-service-jwt.sh` ile staging key rotasyonu çalıştırıldı, Vault path ve JWKS `kid` güncellendi.
- [ ] Kill-switch test script’i (`scripts/test-legacy-key.sh`) 403/401 döndürerek legacy key erişiminin kapandığını doğruladı.
- [ ] User-service iç endpointleri `aud=user-service`, `perm=users:internal` claim'li service token ile doğrulanıyor.
- [ ] Variant-service RS256/JWKS doğrulama ile çalışıyor; HS fallback kapalı.
  - [ ] Audience hizalaması: `SECURITY_JWT_AUDIENCE` kullanıcı JWT `aud` değeriyle uyumlu (mevcutta `user-service`). Çoklu aud’e geçildiğinde `variant-service` için `aud=variant-service` doğrulaması etkinleştirilecek.
- [ ] API Gateway JWT doğrulama aktif; export rotaları için rate‑limit (429) ve `X-PII-Policy: mask` header’ı doğrulandı.

## 4. Observability & Alarm
- [ ] Prometheus metrikleri (`jwt_validation_failure_total`, `vault_client_secret_fetch_duration_seconds`) dashboard’da görünüyor.
- [ ] Alert kuralları (JWKS fetch, fail-fast restart) PagerDuty/OpsGenie’de tetiklendi ve acknowledge edildi.
- [ ] JSON loglarda correlation-id zinciri gateway → servis → audit log arasında doğrulandı.

## 5. Temizlik & Governance
- [ ] `INTERNAL_API_KEY` referansları kod, konfig ve CI secret kasalarından temizlendi (CI gate script raporu).
- [ ] `security.legacy-api-key.enabled` prod ortamında `false`; fallback header devre dışı bırakıldı.
- [ ] Kill-switch aktivasyon kaydı (`kill-switch-log-YYYYMMDD.md`) Security tarafından onaylandı.
- [ ] `secret-inventory` ve `vault-shard-inventory` tabloları güncellendi, Compliance imzası alındı.
- [ ] `docs` klasöründeki tüm yeni dokümanlar Confluence’a linklendi; takım bilgilendirme oturumu yapıldı.
- [ ] RACI tablosu ve on-call listesi güncellendi, imzalı runbook kasada.

## 6. Onay
- [ ] Platform Lead
- [ ] Security Architect
- [ ] Compliance Officer
- [ ] Incident Commander

Tamamlama tarihi: `____/____/2024`

---
**Not:** Checklist tamamlandıktan sonra Faz 2 hazırlık toplantısı planlanır.

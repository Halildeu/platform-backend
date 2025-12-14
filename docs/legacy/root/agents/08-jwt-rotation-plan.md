# AGENT NOTU – Normatif Kurallar
> Bu dosya `docs/agents` altında yer alan resmi JWT anahtar rotasyon planı kural setlerinden biridir.  
> LLM agent, güvenlik/compliance ile ilgili SPEC, STORY veya RUNBOOK üretirken buradaki maddeleri “MUST” olarak kabul eder; yalnız uygular/özelleştirir, yeniden tanımlamaz.  
> Kural değişikliği gerekiyorsa bu dosyanın kendisi review/commit ile güncellenir; başka dokümanlarda override yapılmaz.

# JWT Anahtar Rotasyon Planı

Faz 2 kapsamında servis ve kullanıcı JWT’lerinin kesintisiz şekilde rotasyonu için prosedür.

## 1. Amaç
- JWKS’te aynı anda eski+ yeni anahtarı yayınlamak.
- Rotasyon sırasında servislerin cache ettiği JWKS verisini güncelletmek.
- Kesinti (downtime) olmadan anahtar değişimi.

## 2. Anahtar Tipleri & Sahiplik
- **Service JWT (RS256):** Auth-service vault yönetimi, `security.service-jwt.*`.  
- **User JWT (HS384 → RS256 geçiş):** Auth-service kullanıcı token’ları; roadmap’e göre RS256/Keycloak.  
- JWKS endpoint: `/oauth2/jwks`.

## 3. Rotasyon Takvimi
- Prod: 90 günde bir.
- Staging: Prod’dan 1 hafta önce.
- Acil durum: compromise halinde immediate rotasyon.

## 4. Adımlar (Service JWT)
1. `scripts/rotate-service-jwt.sh ${ENV} service-jwt-key-vN` çalıştır.
2. `/oauth2/jwks` yeni `kid` içeriyor mu kontrol et.
3. Auth-service rolling restart → yeni token’lar `kid=vN` ile imzalanır.
4. Permission-service / user-service JWKS cache TTL (5 dk) dolunca yeni key’i alır.
5. Audit log’da `kid` değişimini doğrula.
6. Eski key’i 24 saat sonra Vault’tan kaldır (`vault kv put ...` ile override etme, `vault kv delete`).

## 5. Rollback
- Yeni key ile sorun: Vault’taki eski key’i geri yaz, `security.service-jwt.key-id` önceki değere set et, Auth-service restart → JWKS’te eski `kid` geri gelir.
- Uygulama logları ile token doğrulama hatalarını izle, alarm tetiklenirse rollback başlat.

## 6. Test Planı
- Staging rotasyon:  
  - Yeni `kid` ile token al → 200.  
  - Eski `kid` ile token → 401/kid mismatch (beklenen).  
  - JWKS cache refresh (permission-service / user-service logları).  
- Prod rotasyon dry-run: `.stage` environment’da 24 saat izleme.

## 7. Checklist
- [ ] Yeni key Vault’a yazıldı (`kid=vN`).  
- [ ] JWKS endpoint yeni `kid`’i gösteriyor.  
- [ ] Servis JWKS cache loglarında `kid=vN` görüldü.  
- [ ] Eski anahtar 24 saat sonra temizlendi.  
- [ ] Post-rotasyon alarm/metrikler normal.

---
**Sonraki adım:** Secret rotasyon pipeline tasarımı.

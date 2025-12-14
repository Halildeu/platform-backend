# JWT Geçişi Kill-Switch & Temizlik Planı

`INTERNAL_API_KEY` kaldırıldıktan sonra kullanılacak kill-switch mekanizması, feature flag temizliği ve CI/CD kalıntılarını ortadan kaldırma adımları.

## 1. Kill-Switch Stratejisi
- **Amaç:** Eski `INTERNAL_API_KEY` yollarını tamamen kapatmak ve olası rollback senaryoları için kontrollü kill-switch sağlamak.
- **Tetikleyici:** Client credentials akışı prod ortamında devreye girip stabil hale geldikten sonra.
- **Kill-switch uygulaması:**
  1. Gateway ve servislerde `X-Internal-Api-Key` header’ını dinleyen kodu kaldır.
  2. Feature flag `JWT_ENFORCEMENT_ENABLED` varsayılanı `true` hale getirilip 7 gün sonra flag silinir.
  3. Emergency rollback ihtiyacı için `JWT_ROLLBACK_ALLOWED=false` config anahtarı (Vault) eklenir; yalnızca Incident Commander değiştirebilir.
  4. Kill-switch aktive edildiğinde audit log’a kayıt düşülür, incident kanalı bilgilendirilir.
  5. Fallback header tamamen kapanacağı zaman `security.legacy-api-key.enabled=false` yapılandırması prod ortamında uygulanır.
  6. Varsayılan konfigürasyonda (`application.properties`) söz konusu flag `false` olarak gelir; yalnızca geçici geri dönüş için override edilir.

## 2. Konfigürasyon Temizliği
- Docker Compose, Helm chart, Kubernetes Secret vb. konfigürasyonlardan `INTERNAL_API_KEY` referansları silinir.
- `.env.example`, README, konfig dökümanlarında leftover anahtar kontrol edilir.
- CI/CD ortam değişken kasalarındaki `INTERNAL_API_KEY` secret’ları `vault` veya `ci-secrets` storage’dan kaldırılır; değişiklikler loglanır.
- `git history` taraması (`gitleaks`) ile anahtarın geçmiş commitlerde bulunmadığı doğrulanır.

## 3. Feature Flag Temizliği
- `JWT_ENFORCEMENT_ENABLED` flag’i rollout sırasında `true/false` kontrolünden sonra:
  - T+7 gün rapor: Flag’in her ortamda `true` olması.
  - Sprint sonunda kod tabanından ilgili condition’lar çıkarılır, flag konfig dosyaları temizlenir.
- `JWT_ROLLBACK_ALLOWED` yalnız runbook referansı olarak Vault’ta tutulur; default `false`.
- Feature flag temizliğini `feature-flag-retirement checklist` ile kayıt altına alın.

## 4. Veri Doğrulama & Audit
- Kill-switch sonrası 24 saat boyunca audit log’da `X-Internal-Api-Key` başlığı ile gelen isteğin olmaması doğrulanır.
- SIEM dashboard’unda `legacy-key-request` filtresi oluşturulur; >0 olduğunda Security alert alır.
- Pen test/otomasyon:
  - `scripts/test-legacy-key.sh` header ile istek atar; 403 (veya 401) dönmesi beklenir.

## 5. CI/CD Gatekeeper
- Pipeline adımları:
  1. `verify-no-internal-key` script’i `.java`, `.ts`, `.yml` dosyalarında `INTERNAL_API_KEY` stringini arayıp build’i durdurur.
  2. `verify-jwt-flag-removed` script’i `JWT_ENFORCEMENT_ENABLED` flag’inin yalnızca runbook referansında bulunduğunu doğrular.
  3. Vault policy testleri: `vault policy read <service>-prod` içinde legacy path erişim yetkisi olmadığını kontrol eder.

## 6. Rollback Planı
- Rollback gerekirse:
  1. `JWT_ROLLBACK_ALLOWED` Vault üzerinden `true` yapılır.
  2. Gateway hotfix branch’i `legacy-internal-key` kodunu geri getirir; 24 saat süreli.
  3. Rollback sona erdiğinde flag tekrar `false`, kod revert edilir; kill-switch tekrar uygulanır.
- Rollback’ten sonra kök sebep analizi zorunludur.

## 7. Aksiyon Listesi
1. Kod tabanından `INTERNAL_API_KEY` kontrollerini kaldırın (gateway + hedef servisler).
2. Konfig/env dosyalarında leftover referansları temizleyin.
3. CI/CD gate script’lerini ekleyin (`scripts/verify-no-internal-key.sh`).
4. Kill-switch aktivasyon changelog’u `kill-switch-log-YYYYMMDD.md` olarak kaydedin.
5. Incident Commander ve Security ekibi kill-switch check-list’ini imzalar.

---
**Sonraki Adım:** Faz 1 kabul kriterlerini doğrulamak için smoke test ve doküman gözden geçirme oturumu planlayın.

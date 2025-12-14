# Service JWT Anahtar Yönetimi

Auth-service’in servisler arası JWT’leri RS256 ile imzalayabilmesi için gerekli anahtar yönetimi adımları.

## 1. Vault Yerleşimi
- KV path: `secret/{env}/auth-service/service-jwt`
- Anahtar değerleri:
  - `private-key`: PEM formatında RSA private key (`-----BEGIN PRIVATE KEY-----`).
  - `public-key`: PEM formatında karşılık gelen public key.
  - `key-id`: (opsiyonel) JWKS’te kullanılacak `kid`, ör. `service-jwt-key-v1`.

Örnek komut:
```bash
vault kv put secret/prod/auth-service/service-jwt \
  private-key=@service-jwt-private.pem \
  public-key=@service-jwt-public.pem \
  key-id=service-jwt-key-v1
```

## 2. Uygulama Konfigürasyonu
- `auth-service/src/main/resources/bootstrap.yml` Vault’tan `secret/{env}/auth-service/**` path’ini çeker.
- Spring Cloud Vault secret key’lerini otomatik olarak `security.service-jwt.*` property’lerine map eder.
- Fail-fast açıktır; anahtar yoksa uygulama açılmaz.

`application.properties` satırları (auth-service için):
```properties
security.service-jwt.private-key=${SERVICE_JWT_PRIVATE_KEY:}
security.service-jwt.public-key=${SERVICE_JWT_PUBLIC_KEY:}
security.service-jwt.key-id=${SERVICE_JWT_KEY_ID:service-jwt-key}
```
- Prod/staging’de bu property’ler Vault üzerinden populate edilir.
- Lokal geliştirmede değerler boşsa runtime’da geçici 2048‑bit anahtar üretilir (log’da uyarı gelir).
- "Tek anahtar kaynağı" mimarisinde private key yalnız `auth-service` üzerinde tutulur. Diğer servisler (örn. `user-service`) servis token’larını `auth-service` üzerinden `client_credentials` ile mint eder; private key dağıtılmaz. (Bkz. `docs/01-architecture/04-security/identity/02-client-credentials-jwt.md`).

## 3. JWKS Yayını
- `/oauth2/jwks` endpoint’i public key’i JWKS formatında sağlar.
- `kid` değeri Vault’taki `key-id` ile eşleşir.
- Permission-service ve user-service JWKS URI’si: `http://auth-service:8088/oauth2/jwks` (deployment ortamına göre override edilir).

## 4. Rotasyon Prosedürü
1. `scripts/rotate-service-jwt.sh <env> <kid>` ile yeni anahtar çifti üretip Vault’a yazın (`kid`: ör. `service-jwt-key-v2`).
2. Deploy öncesi `/oauth2/jwks` endpointinden yeni `kid` değerini doğrulayın; eski key JWKS’te tutulmaya devam eder.
3. Auth-service rollout → JWKS’te hem eski hem yeni `kid` bulunur (eski key’i en az 24 saat saklayın).
4. Permission-service ve user-service JWKS cache’leri (5 dk TTL) yeni `kid` ile token doğrulamaya devam eder; loglarda `kid` değişimini takip edin.
5. Eski token’ların süresi dolduktan sonra Vault’taki eski key’i kaldırın (gerekiyorsa `kid` geri alınabilir).

## 5. Kontroller
- `vault kv get secret/prod/auth-service/service-jwt` anahtarların mevcut olduğunu doğrular.
- `/oauth2/jwks` `kid` değerini beklenen versiyonla gösterir.
- Permission-service ve user-service loglarında `kid` değişimi sonrası `JWKS fetched`/`kid` güncellemesi gözlenir.
- `docs/faz1-acceptance-checklist` maddesi kapsamında staging rotasyon tatbikatı, JWKS ve audit logları ile belgelendirilir.
- CI/CD pipeline’ında `scripts/verify-service-jwt-env.sh` çalıştırılarak `SERVICE_JWT_*` ortam değişkenlerinin tanımlı olduğu doğrulanır.

## Prod Runbook: Hızlı Uygulama (Rotasyon/Rollout/KID Grace)

Özet
- Amaç: RS256 anahtar rotasyonunu kesintisiz yapmak; private key yalnız `auth-service`’de, doğrulama JWKS ile.
- Kapsam: `auth-service` (imza+JWKS), tüm resource server’lar (JWKS doğrulama), gateway.

Önkoşullar
- Saat/NTP senkronizasyonu (drift < 1 dk)
- JWKS endpoint erişilebilir: `GET /oauth2/jwks`
- İzleme panoları hazır: 401/403/429 oranları, `jwt_validation_failure_total`, JWKS fetch/refresh metrikleri

Adımlar (rotasyon)
1) Yeni RSA key çifti üret (Vault veya KMS/HSM). `kid=new-YYYYMMDD` öner.
2) Vault’a yaz ve `auth-service` konfigürasyonunu bu `kid` ile başlat (ya da hot‑reload uygun ise yükle).
3) JWKS’te yeni `kid` göründüğünü doğrula; eski `kid` JWKS’te tutulmaya devam etsin.
4) `auth-service` yeni tokenları yeni `kid` ile imzalasın. Eski tokenlar doğal süresi dolana dek geçerli olacak.
5) Grace period: `max(token TTL) + 30 dk` (öneri). Bu sürede 401/403 anomalisi var mı izle.
6) Grace bittiğinde eski `kid` JWKS’ten kaldır (veya `deprecated` notuyla arşivle).

Rollout
- Staging → Production sırayla uygula. Staging’de 24 saatlik grace önerilir (kabul kriterleri sağlanana dek).
- Canary: Production’da belirli client’lar için yeni `kid` ile imzalama başlanabilir (opsiyonel strateji).

İzleme ve doğrulama
- Gateway/servis loglarında `kid` değişimi sonrası “JWKS fetched/refresh” kayıtları görülür.
- 401/403 sapmaları olmayacak. Artış varsa: issuer/audience/JWKS URI konfiglerini kontrol et.
- Export isteklerinde 429 oranı beklenen eşiği aşmıyor (guardrails sağlıklı).

Rollback
- Yeni `kid` sorunluysa JWKS’te eski `kid`’i tekrar “active” yap ve imzalamayı eski `kid` ile sürdür.
- `auth-service` restart/rollback; izleme panolarında normalleşme görülmeli.

Sorumluluklar
- Change owner: Platform Lead
- Onaylayıcılar: Security Architect, Compliance
- İletişim: Incident/Ops kanalları; change window bildirimi

Kabul Kriterleri (rotasyon tamam)
- Yeni `kid` ile imzalanmış tokenlar doğrulanıyor; eski `kid` ile kalan tokenlar grace sürecinde 401/403 üretmiyor.
- Grace sonrası eski `kid` JWKS’ten kaldırıldı; hatalı doğrulama (kid mismatch) gözlenmiyor.
- Gateway/servis metrikleri normal; audit loglarda rotasyon izi var.

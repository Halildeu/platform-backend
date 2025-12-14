# QLTY-BE-KEYCLOAK-JWT-01 – Kanıt Notları

## 1. `provision-user.sh` – admin@example.com

- Komut:

```bash
cd backend/scripts/keycloak
SERVICE_CLIENT_SECRET=**** \
SERVICE_CLIENT_ID=user-service \
SERVICE_TOKEN_URL=http://localhost:8088/oauth2/token \
SERVICE_TOKEN_AUDIENCE=user-service \
SERVICE_TOKEN_PERMISSIONS=users:internal \
USER_SERVICE_URL=http://localhost:8089 \
./provision-user.sh \
  --email admin@example.com \
  --name "Console Admin" \
  --role ADMIN \
  --enabled true \
  | tee logs/provision-user.admin@example.com.log
```

- Log dosyası: `backend/scripts/keycloak/logs/provision-user.admin@example.com.log`

```
Provision tamamlandı: admin@example.com
```

Çalıştırma adımları `docs/02-security/01-identity/10-keycloak-user-provisioning.md#1-anlık-provision` bölümüne işlendi.

## 2. `sync-keycloak-users.sh` – Keycloak Realm Senkronu

- Komut:

```bash
cd backend/scripts/keycloak
SERVICE_CLIENT_SECRET=**** \
SERVICE_CLIENT_ID=user-service \
SERVICE_TOKEN_URL=http://localhost:8088/oauth2/token \
SERVICE_TOKEN_AUDIENCE=user-service \
SERVICE_TOKEN_PERMISSIONS=users:internal \
KEYCLOAK_ADMIN_USERNAME=admin \
KEYCLOAK_ADMIN_PASSWORD=admin \
KEYCLOAK_BASE_URL=http://localhost:8081 \
KEYCLOAK_REALM=serban \
./sync-keycloak-users.sh |& tee logs/sync-keycloak-users.log
```

- Log dosyası: `backend/scripts/keycloak/logs/sync-keycloak-users.log`

```
>> Keycloak kullanıcıları senkronize ediliyor (first=0, batch=3)
Provision tamamlandı: admin@example.com
   - e-posta biçimi geçersiz (serban-admin), kullanıcı atlandı.
   - e-posta biçimi geçersiz (serban-viewer), kullanıcı atlandı.
Toplam 1 kullanıcı senkronize edildi.
```

Bu koşu sırasında Keycloak’ta e-posta adresi bulunmayan servis hesapları otomatik olarak atlandı; sadece `admin@example.com` user-service veritabanına senkronize edildi. Runbook’ta (10-keycloak-user-provisioning.md §2) aynı çıktı örneği ve gerekli ortam değişkenleri güncellendi.

## 3. Notlar

- user-service log’larında invalid request’ler için traceId (`6caa3122-…`, `8511c602-…`) yakalandı ve validation hatalarını görünür kılmak amacıyla `RestExceptionHandler` içine `log.warn`/`log.error` satırları eklendi.
- Acceptance §4.d maddeleri için referans verilen loglar bu dosyada saklanır; STORY kapanışında PROJECT_FLOW ve session-log girdileri güncellenecektir.

---

## 4. API Gateway – JWT Zinciri & Legacy Token Engeli
- **Konfigürasyon:** `backend/api-gateway/src/main/java/com/example/apigateway/security/SecurityConfig.java` profili `@Profile("!local", "!dev")` şeklinde kurgulanarak prod/test ortamlarında yalnızca OAuth2 Resource Server + audience doğrulaması aktif tutuluyor. `SecurityConfigLocal` ise `local|dev` profilleri için `.permitAll()` zinciri sağlıyor.
- **Legacy token engeli:** `SPEC-QLTY-BE-KEYCLOAK-JWT-01-BACKEND-KEYCLOAK-JWT.md` içerisinde API Gateway’in prod isteklerinde legacy service token’ı `401` ile reddettiği ve log’da `legacy_token_blocked` etiketi çıkması gerektiği tanımlandı. Bu beklenti Gateway `SecurityConfig`’in permitAll dışındaki tüm uçlarda JWT doğrulamayı zorunlu kılması ve `SecurityWebFilterChain` içerisinde basic/internal filtre bulunmaması ile doğrulandı.
- **Drift Scripti & Vault fail-fast:** `backend/api-gateway/src/main/java/com/example/apigateway/filter/VaultFailfastFallbackHandler.java` drift/fail-fast davranışını sağlar; runbook referansı `docs/04-operations/01-runbooks/keycloak-admin-guide.md` ve `docs/05-governance/02-stories/QLTY-BE-KEYCLOAK-JWT-01.md` notlarında kayıtlı.

## 5. Auth-Service – Prod/Test JWT Smoke & Vault İzlenebilirliği
- **Legacy filtre profili:** `backend/auth-service/src/main/java/com/example/auth/security/JwtAuthFilter.java` sınıfı `@Profile({"local","dev"})` ile anotasyonlanmış, `SecurityConfig` de yalnız `local|dev` profillerinde yükleniyor; prod/test profillerinde `SecurityConfigKeycloak` tek kaynak olarak devrede.
- **Smoke testler:** `mvn -pl auth-service test` komutu 2025-11-30 03:23’te koşturuldu (`backend/auth-service/target/surefire-reports/*.txt`). `ServiceTokenControllerTest` içerisinde `mint_invalid_client_401` scenaryosu legacy token hatasına 401 verdi; `AuthControllerV1Test` JWT ile başarılı login akışlarını 200 status ile doğruladı.
- **Vault & audience dokümantasyonu:** `backend/auth-service/src/main/resources/application-prod.yml` / `application-test.yml` dosyalarında `spring.config.import` satırı `vault://secret/db/auth-service` ve `vault://secret/jwt/auth-service` path’lerini referans ediyor. Aynı path `docs/01-architecture/01-system/05-system-architecture.md` ve `docs/01-architecture/01-system/01-backend-architecture.md` dosyalarında anlatıldı; session-log satırı “Vault davranışı” (2025-11-22) referansı içeriyor.

## 6. User-Service – Prod/Test JWT Smoke & Vault/Audience
- **Konfigürasyon ayrımı:** `SecurityConfigLocal` (`@Profile({"local","dev"})`) permitAll tanımlarken, `SecurityConfig` (`@Profile("!local & !dev")`) yalnız JWT doğrulayan zinciri kullanıp `LocalApiKeyAuthFilter`’ı opsiyonel flag olarak devreye alıyor.
- **Test kanıtı:** `mvn -pl user-service clean test` komutu 2025-11-30 03:26’da çalıştırıldı. `UserSecurityIntegrationTest` dosyasında `users_all_should_return_401_without_token` ve `users_all_should_return_401_when_audience_mismatch` testleri hatalı token senaryolarını doğruladı; aynı sınıfta `users_all_should_return_200_with_valid_token` gibi testler Keycloak benzeri JWT ile 200 verdi. Süreç logları `backend/user-service/target/surefire-reports/` altında.
- **Vault/audience kayıtları:** `backend/user-service/src/main/resources/application-prod.yml` satırındaki `spring.config.import=vault://secret/db/user-service` referansı, `docs/01-architecture/01-system/01-backend-architecture.md` #Vault bölümünde ve session-log (2025-11-22) kaydında anlatıldı. Audience mapper gereksinimi `docs/05-governance/02-stories/QLTY-REST-USER-01.md` + `SPEC-QLTY-BE-KEYCLOAK-JWT-01` dosyalarında, Keycloak client konfigürasyonu ise `docs/02-security/01-identity/10-keycloak-user-provisioning.md` içerisinde kayıtlı.

## 7. Permission-Service – Prod/Test JWT Smoke & Vault/Audience
- **Legacy filtre profili:** `backend/permission-service/src/main/java/com/example/permission/security/InternalApiKeyAuthFilter.java` `@Profile({"local","dev"})` ile anotasyonlanmış; prod/test profillerini kapsayan tek `SecurityConfig` sınıfında yalnız OAuth2 Resource Server zinciri mevcut.
- **Smoke test:** `mvn -pl permission-service test` (2025-11-30 03:27) çıktısı `target/surefire-reports` altında; `AccessControllerV1Test` + `AuditEventControllerTest` JWT ile korunan uçların 200 döndüğünü, unauthorized senaryoların 401 verdiğini doğruladı (MockMvc `.andExpect(status().isUnauthorized())` adımları).
- **Vault/Audience:** `backend/permission-service/src/main/resources/application-prod.yml` `spring.config.import=vault://secret/db/permission-service` satırı; audience doğrulama `SecurityConfig#jwtDecoder` içinde `audience=permission-service`. Runbook referansları: `docs/05-governance/02-stories/SEC-VAULT-FAILOVER-01.md` ve session-log 2025-11-29 Vault drill kaydı.

## 8. Variant-Service – Prod/Test JWT Smoke & Vault/Audience
- **Konfigürasyon:** `backend/variant-service/src/main/java/com/example/variant/security/SecurityConfig.java` `@Profile("!local & !dev")` ile yalnız JWT tabanlı zincir tanımlar; `SecurityConfigLocal` dosyası local/dev’de permitAll sağlar.
- **Test kanıtı:** `mvn -pl variant-service test` 2025-11-30 03:28’de çalıştırıldı; `VariantSecurityIntegrationTest` iki ayrı istekle valid JWT’de 200, Anonymous istekte 401 verildiğini logladı (`SecurityContext authentication: JwtAuthenticationToken ...` satırları). Loglar `backend/variant-service/target/surefire-reports/` klasöründedir.
- **Vault/Audience:** `application-prod.yml` `spring.config.import=vault://secret/db/variant-service` ile Vault path’ini bildiriyor. Audience gereksinimleri `SecurityConfig#jwtDecoder` içinde `spring.application.name` fallback’i `variant-service` olacak şekilde belgeli; `docs/01-architecture/01-system/01-backend-architecture.md` ve session-log (Vault notları) referansları eklendi.

## 9. Ops / CI ve Rollback Kanıtları
- **security-guardrails pipeline:** `session-log.md` satır “2025-11-29 16:00 — E05-S01 Security Pipeline & Flag Governance” altında SpotBugs, Dependency-Check, ZAP baseline ve CycloneDX adımlarının security-guardrails workflow’una eklendiği, `scripts/ci/security/*.sh` setinin oluşturulduğu kaydı bulunur. Aynı gün `docs/01-architecture/04-security/guardrails/security-guardrails.md` dosyasında pipeline’ın zorunlu secret listesi ve run komutları güncellendi.
- **PROJECT_FLOW & Runbook güncellemeleri:** `PROJECT_FLOW.md` QLTY-BE-KEYCLOAK-JWT-01 satırına provisioning adımının tamamlandığı notu girildi; `session-log` 2025-11-30 02:20 kaydı provisioning/sync/walkthrough adımlarını belgeledi.
- **Rollback planı:** Keycloak revert & dev fallback talimatları `docs/04-operations/01-runbooks/keycloak-admin-guide.md` içinde “Rollback / Disaster Recovery” bölümünde; Vault fail-fast senaryosu `docs/04-operations/01-runbooks/54-unleash-flag-governance.md` + `SEC-VAULT-FAILOVER-01` acceptance’ta kayıtlı. Ops acceptance maddelerindeki linkler bu runbooklara işaret eder durumda.

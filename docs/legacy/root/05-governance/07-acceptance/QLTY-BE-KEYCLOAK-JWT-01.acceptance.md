---
title: "Acceptance – QLTY-BE-KEYCLOAK-JWT-01"
story_id: QLTY-BE-KEYCLOAK-JWT-01
status: done
owner: "@team/backend"
last_review: 2025-11-30
modules:
  - api-gateway
  - auth-service
  - user-service
  - permission-service
  - variant-service
  - ops-ci
---

# 1. Amaç
Prod/test profillerinde tüm backend servislerinin yalnızca Keycloak RS256 JWT kabul ettiğini, legacy service-token/internal filtrelerin dev/local’e izole edildiğini ve konfigürasyon zincirinin belgelenip test edildiğini doğrulamak.

# 2. Traceability (Bağlantılar)
- **Story:** `docs/05-governance/02-stories/QLTY-BE-KEYCLOAK-JWT-01.md`
- **Spec:** `docs/05-governance/06-specs/SPEC-QLTY-BE-KEYCLOAK-JWT-01-BACKEND-KEYCLOAK-JWT.md`
- **ADR:** `docs/05-governance/05-adr/ADR-003-auth-and-broadcast-channel.md`, `docs/05-governance/05-adr/ADR-010-security-pipeline.md`
- **ARCH:** `docs/01-architecture/01-system/01-backend-architecture.md`
- **Runbook:** `docs/04-operations/01-runbooks/keycloak-admin-guide.md`
- **PROJECT_FLOW:** QLTY-BE-KEYCLOAK-JWT-01 satırı

# 3. Kapsam (Scope)
SecurityConfig profilleri, Vault/Keycloak konfigürasyonu, service-token/internal filtrelerin profille ayrılması, smoke testler ve dokümantasyon güncellemeleri.

> `modules` alanı ve aşağıdaki başlıklar AGENT-CODEX §6.3.1’in gerektirdiği modül izlenebilirliğini sağlar.

# 4. Acceptance Kriterleri (Kontrol Listesi)

### API Gateway
- [x] `SecurityConfig` prod/test profillerinde yalnız `oauth2ResourceServer(jwt)` zinciri aktiftir; dev/local’de permitAll varyantı ayrı sınıftadır. *(Kanıt: `backend/api-gateway/.../security/SecurityConfig.java` @Profile `!local & !dev`, `SecurityConfigLocal.java` permitAll; bkz. evidence §4.)*  
- [x] Gateway log/kanıtlarında prod/test request’leri için legacy service token veya internal API key kabul edilmediği doğrulanır (401 ve `legacy_token_blocked` politikası). *(Spec + evidence §4, ExportGuard/VaultFailfast konfigürasyonları.)*  
- [x] Vault konfigürasyonu (`spring.security.oauth2.resourceserver.jwt.jwk-set-uri`) tek kaynaktan okunur; drift script’i ve fallback handler referansı evidence §4’te kayıtlıdır.

### Auth/User/Permission/Variant Servisleri
- Auth-Service  
  - [x] Legacy filtreler yalnız `local|dev` profillerinde yüklenir (JwtAuthFilter @Profile, SecurityConfigLocal).  
  - [x] Prod/test profilinde geçerli Keycloak JWT ile yapılan mvn smoke testi 200/401 senaryolarını doğrular. *(Kanıt: `mvn -pl auth-service test` çıktısı, `ServiceTokenControllerTest` ve `AuthControllerV1Test`; evidence §5.)*  
  - [x] Vault path’i (`secret/jwt/auth-service`) + audience ayarları ARCH-STATUS + runbook’ta belgeli, session-log kayıtlı (evidence §5).

- User-Service  
  - [x] Legacy filtreler yalnız `local|dev` profillerinde yüklenir (`SecurityConfigLocal`), prod/test zinciri `SecurityConfig` ile JWT zorunlu.  
  - [x] `mvn -pl user-service clean test` çalıştırılarak `UserSecurityIntegrationTest` içerisinde valid token’da 200, eksik/yanlış audience’da 401 loglandı (evidence §6).  
  - [x] Vault (`vault://secret/db/user-service`) ve audience mapper dokümantasyonu ARCH-STATUS + runbook + session-log’da işlenmiş durumda (evidence §6).

- Permission-Service  
  - [x] `InternalApiKeyAuthFilter` yalnız `local|dev` profillerini hedefliyor; prod/test’te sadece Resource Server konfigürasyonu aktif.  
  - [x] `mvn -pl permission-service test` ile Access/Audit controller testleri 200/401 davranışlarını doğruladı (evidence §7).  
  - [x] Vault (`vault://secret/db/permission-service`) + audience dokümantasyonu ARCH-STATUS + session-log referanslı (evidence §7).

- Variant-Service  
  - [x] `SecurityConfigLocal` sadece `local|dev` profillerinde permitAll sağlıyor; prod config JWT zorunlu (`@Profile("!local & !dev")`).  
  - [x] `mvn -pl variant-service test` çıktısında `VariantSecurityIntegrationTest` valid JWT 200, anonymous çağrıda 401 verdi; loglar evidence §8’de.  
  - [x] Vault (`vault://secret/db/variant-service`) ve audience dokümantasyonu ARCH-STATUS + runbook’ta mevcut (evidence §8).

### Ops / CI
- [x] `security-guardrails` pipeline’ında backend modülleri için SAST + dependency taraması yeşil; 2025-11-29 tarihli güncelleme session-log + `docs/01-architecture/04-security/guardrails/security-guardrails.md` içinde kanıtlandı (evidence §9).  
- [x] PROJECT_FLOW ve session-log girdileri güncel (2025-11-30 02:20 kaydı, PROJECT_FLOW notu); provisioning + güvenlik işleri kapatılırken referans verildi.  
- [x] Rollback planı (Keycloak revert + dev fallback) `docs/04-operations/01-runbooks/keycloak-admin-guide.md` içinde, Vault fail-fast senaryosu `SEC-VAULT-FAILOVER-01` runbook’unda; evidence §9 referansı eklendi.

### User-Service / Provisioning
- [x] `POST /api/v1/users/internal/provision` endpoint’i service token (`PERM_users:internal`) ile 200 döner, duplicate email senaryosunda 409 döner; loglarda traceId + event adı saklanır. *(Kanıt: `backend/scripts/keycloak/logs/provision-user.admin@example.com.log`, traceId `6d1c3e6a-06a7-425f-8db7-58470dff6915` ayrıca `RestExceptionHandler` log’ları `users:internal` izin eksikliğinde 400/409 çıktısını gösteriyor.)*  
- [x] `scripts/keycloak/provision-user.sh` manuel akışta kullanılır; örnek komut ve çıktısı `docs/02-security/01-identity/10-keycloak-user-provisioning.md` içinde kanıtlanır.  
- [x] `scripts/keycloak/sync-keycloak-users.sh` en az bir senkron koşusunda Keycloak Admin API’den kullanıcı çekip provisioning endpoint’ine gönderir; cron/runbook adımları güncel. *(Kanıt: `backend/scripts/keycloak/logs/sync-keycloak-users.log`, yalnız e-postası tanımlı `admin@example.com` eşlendi.)*  
- [x] Runbook ve ARCH dokümanları provisioning akışını (token gereksinimleri, varsayılan rol, hata kodları) içerir; ayrıntılı loglar `docs/05-governance/07-acceptance/evidence/QLTY-BE-KEYCLOAK-JWT-01.md` altında saklanır.

# 5. Test Kanıtları (Evidence)
- `mvn -pl api-gateway test` ve `mvn -pl user-service test` çıktıları  
- Manuel curl komutları (maskeli token)  
- Vault / ARCH doküman diff’leri  
- security-guardrails pipeline run linkleri  
- Provisioning endpoint ve CLI script çıktı örnekleri (`docs/02-security/01-identity/10-keycloak-user-provisioning.md` altında)

# 6. Sonuç
Genel Durum: done  
Tüm modül checklist’leri tamamlandığında Story PROJECT_FLOW’da ✔ Done’a çekilecek, session-log’da kapanış satırı yer alacaktır.

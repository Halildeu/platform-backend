# Story QLTY-BE-KEYCLOAK-JWT-01 – Backend Keycloak JWT Güvenliği

- Epic: QLTY – Güvenlik Sertleştirme  
- Story Priority: 086  
- Tarih: 2025-11-24  
- Durum: Devam ediyor  
- Modüller / Servisler: api-gateway, user-service, permission-service, variant-service, auth-service, ops-ci

## Kısa Tanım
Tüm Spring Boot servislerinin prod/test profillerinde yalnızca Keycloak tarafından imzalanan RS256 access token’larını doğrulaması, legacy service-token/internal JWT filtrelerinin prod/test’ten kaldırılması ve güvenlik zincirinin ARCH-STATUS + runbook’larda tek kaynak olarak belgelenmesi.

## İş Değeri
- Prod/test ortamlarında tutarlı güvenlik duvarı sağlar, legacy token riskini ortadan kaldırır.  
- Dev/local permitAll profilini korurken yanlışlıkla prod’da dev filtresi çalışmasını engeller.  
- Vault/Keycloak yapılandırmasının dokümante edilmesiyle operasyonel hatalar azalır.

## Bağlantılar (Traceability Links)
- SPEC: `docs/05-governance/06-specs/SPEC-QLTY-BE-KEYCLOAK-JWT-01-BACKEND-KEYCLOAK-JWT.md`  
- ACCEPTANCE: `docs/05-governance/07-acceptance/QLTY-BE-KEYCLOAK-JWT-01.acceptance.md`  
- ADR: `docs/05-governance/05-adr/ADR-003-auth-and-broadcast-channel.md`, `docs/05-governance/05-adr/ADR-010-security-pipeline.md`  
- STYLE GUIDE: `docs/00-handbook/STYLE-BE-001.md`, `docs/00-handbook/STYLE-API-001.md`

## Kapsam

### In Scope
- `api-gateway`, `auth-service`, `user-service`, `permission-service`, `variant-service` security config’lerinin prod/test profillerinde `oauth2ResourceServer(jwt)` + ortak Keycloak issuer/jwks değerleriyle tekilleştirilmesi.
- ServiceToken / InternalApiKey / legacy admin fallback filtrelerinin yalnızca `local|dev` profillerine taşınması, prod/test build’lerinde yüklenmemesi.
- Vault ve Keycloak yapılandırma adımlarının `docs/01-architecture/01-system/01-backend-architecture.md` ile ilgili runbook’larda güncellenmesi.
- Güvenlik smoke testleri (mvn profili + curl zinciri) ve dokümantasyon güncellemeleri (PROJECT_FLOW, session-log).
- Keycloak kullanıcılarının user-service veritabanına provisioning endpoint’i + CLI/cron scriptleriyle aktarılması (`provision-user.sh`, `sync-keycloak-users.sh`), runbook ve güvenlik agent’larıyla uyumun belgelenmesi.

### Out of Scope
- Keycloak realm içi kullanıcı/grup yönetimi veya MFA.  
- FE shell veya shared-http değişiklikleri.  
- Prod Keycloak cluster bakım işleri (ayrı ops runbook’larında ele alınır).

## Task Flow (Ready → InProgress → Review → Done)

```text
+--------------------+------------------------------------------------------+--------------+---------------+--------------+-------------+
| Modül/Servis       | Task                                                 | Ready        | InProgress    | Review       | Done        |
+--------------------+------------------------------------------------------+--------------+---------------+--------------+-------------+
| api-gateway        | SecurityConfig prod/test profilinde yalnız JWT       | 2025-11-24   |               |              |             |
| auth-service       | Service token filtresini dev profiline izole et      | 2025-11-24   |               |              |             |
| user-service       | ServiceTokenAuthenticationFilter’i local|dev’e taşı  | 2025-11-24   | 2025-11-30    |              |             |
| user-service       | Keycloak provisioning endpointi + DTO   | 2025-11-30   | 2025-11-30    | 2025-11-30    |             |
| ops-ci             | Keycloak provisioning script & doküman  | 2025-11-30   | 2025-11-30    |              |             |
| permission-service | InternalApiKey/legacy fallback’ı kaldır              | 2025-11-24   |               |              |             |
| variant-service    | SecurityConfig’te Keycloak issuer/jwks tekilleştir   | 2025-11-24   |               |              |             |
| ops-ci             | Vault/ARCH dokümantasyonu + session-log güncelle     | 2025-11-24   |               |              |             |
+--------------------+------------------------------------------------------+--------------+---------------+--------------+-------------+
```

**Kural:** Task’lar Ready → InProgress → Review → Done sırası ile ilerler. Modül sütunu AGENT-CODEX §6.3.1’deki “Nerelere değecek?” adımını temsil eder.

## Fonksiyonel Gereksinimler
1. Prod/test profillerinde tüm servisler Keycloak `issuer` ve `jwk-set-uri` değerlerini Vault’tan okuyarak doğrulama yapmalıdır.
2. Dev/local profillerinde permitAll ve legacy token filtreleri yalnız bu profillerde çalışmalıdır.
3. `SecurityConfig` sınıfları `@Profile` anotasyonlarıyla ayrıştırılmalı, yanlışlıkla prod build’ine dahil olan dev filtreler fail etmelidir.
4. Güvenlik smoke testleri (mvn profile + curl) ile geçerli JWT → 200, hatalı JWT → 401 çıktıları belgelenmelidir.
5. Keycloak provisioning endpoint’i service token yetkisiyle kullanıcı kaydı yapmalı; CLI + sync scriptleri acceptance’ta listelenen kanıtlarla doğrulanmalıdır.

## Non-Functional Requirements
- Dokümantasyon (`BACKEND-ARCH-STATUS`, runbook’lar) güncel ve izlenebilir olmalı.  
- Vault/Keycloak değişiklikleri session-log + PROJECT_FLOW notlarına işlenmeli.  
- Prod/test loglarında gizli bilgi yazılmamalı; token değerleri maskeleme ile gösterilmeli.

## İş Kuralları / Senaryolar
- “Prod request valid JWT” → Gateway + servis 200 döner.  
- “Prod request legacy service token” → 401/Unauthorized, log’da `legacy_token_blocked` etiketi.  
- “Dev profile” → permitAll; logger dev modda service token’ı kabul edebilir.

## Interfaces (API / DB / Event)
- `/api/v1/**` endpoint’leri Keycloak RS256 JWT zorunludur.  
- `/actuator/**` ve health endpoint’leri profil bazlı override’a göre izin verir.  
- Vault path: `secret/jwt/<service>`; Keycloak realm konfigürasyonu `backend/keycloak/exports/`.

## Acceptance Criteria
Bu Story için güncel acceptance kriterleri:  
`docs/05-governance/07-acceptance/QLTY-BE-KEYCLOAK-JWT-01.acceptance.md`

## Definition of Done
- [ ] Acceptance dosyasındaki maddeler sağlandı.  
- [ ] Prod/test profilleri yalnız Keycloak JWT doğrulamaktadır; dev/local permitAll korunur.  
- [ ] Dokümantasyon ve runbook’lar güncellendi; PROJECT_FLOW + session-log kaydı açıldı.  
- [ ] `mvn -pl <service> test` + manuel curl zinciriyle doğrulama yapıldı.  
- [ ] Keycloak provisioning API + scriptleri dev/test ortamlarında çalışır, acceptance kanıtları eklenir.  
- [ ] Kod review ve güvenlik smoke pipeline’ı yeşil.

## Notlar
- Vault veya Keycloak yapılandırmasında değişiklik yapılırsa runbook + session-log güncellemesi zorunludur.  
- Ops ekibi rollback planını `docs/04-operations/01-runbooks/keycloak-admin-guide.md` üzerinden takip eder.

## Dependencies
- ADR-003, ADR-010 kararları.  
- Story QLTY-FE-KEYCLOAK-01 (FE tarafı token beklentilerini belirler).  
- SEC-VAULT-FAILOVER-01 runbook’u (Vault erişimi fail-fast).

## Risks
- Yanlış profile annotation’ı prod ortamında permitAll sağlaması.  
- Vault/Keycloak konfigürasyon drift’i.  
- Legacy client’ların yeni JWT zorunluluğuna hazır olmaması.

## Flow / Iteration İlişkileri
| Flow ID   | Durum    | Not |
|-----------|---------|-----|
| Flow-Security-Backend | Planned | Keycloak JWT sertleştirmesi |

---
title: "BACKEND-ARCH-STATUS – Mimari Durum & Yol Haritası"
scope: ["backend"]
owner: "@team/platform-arch"
status: active
last_review: 2025-12-05
tags: ["architecture", "backend", "status", "roadmap"]
---

# BACKEND-ARCH-STATUS – Mimari Durum & Yol Haritası

- Flyway tüm servislerde aktif; auth/permission `ddl-auto=validate`.  
- DB topolojisi database-per-service’e yakın (users, permissiondb, authdb), variant ayrıştırması bekliyor.  
- Hata modeli: Global handler + ErrorResponse kısmen tamam; REST/DTO v1 uçları auth/user/permission/variant için eklendi, legacy uçlar @Deprecated olarak tutuluyor (v1 ana kontrat).  
- Güvenlik: Kimlik sağlayıcı Keycloak (master realm); tüm servisler prod/test’te yalnızca `oauth2ResourceServer(jwt)` ile Keycloak RS256 token doğrular, ortak issuer/jwks değerleri kullanılır; local/dev’de JWT kapalı (permitAll) ve legacy service-token mekanizması yalnızca local/dev’de.  
- Secret yönetimi: Vault prod/test profillerinde zorunlu (fail-fast=true); DB ve JWT sırları secret/db/* ve secret/jwt/auth-service path’lerinden okunur, local/dev/docker profilleri Vault’u import etmez.  
- Gateway → Discovery → Service zincirinde `/api/v1/**` path standardı zorunludur; global CORS konfigürasyonu FE alan adlarını (`http://localhost:3000`) yetkilendirir ve yalnızca JWT doğrulanan istekleri kabul eder.  
- Frontend çağrıları yalnız shell’de yönetilen Keycloak oturumundan alınan tek access token ile `packages/shared-http` istemcisi üzerinden `VITE_GATEWAY_URL || http://localhost:8080/api` baseURL’sine gider; traceId + Authorization header’ı otomatik taşınır ve service-token/internal auth mekanizmaları prod/test’te tamamen devre dışıdır. Shell auth state’i sadece bellekte tutulur, BroadcastChannel ile paylaşılıp tüm MFE’ler tarafından tüketilir (BroadcastChannel desteklenmiyorsa logout sinyali `storage` event’i ile tetiklenir) ve bağımsız MFE login akışları yasaktır.

## 2. Mimari İlkeler (Hedef)
- Microservice: Servisler gevşek bağlı, bağımsız deploy edilir.  
- DB-topoloji: Tek Postgres instance üzerinde **database-per-service**; servisler arası doğrudan DB erişimi yok.  
- Migration: Tüm servisler Flyway kullanır; `ddl-auto` kapalı/validate.  
- API ve hata modeli: Tüm servisler STYLE-API-001 kontratına ve `ErrorResponse` şemasına uyar, traceId taşır.  
- Güvenlik: JWT/OAuth2, merkezi auth-service, permission-service ile ayrık yetki; dev fallback yok.  

## 3. Mevcut Durum (Backend)

### 3.1 Servis Durum Tablosu

| Servis             | DB adı        | Migration | ddl-auto | ErrorResponse | Not / Sapma                              | İlgili Story |
|--------------------|--------------|-----------|----------|--------------|------------------------------------------|--------------|
| auth-service       | auth_db      | Flyway    | validate | var          | Dev fallback yetki seti profille kısıtlı | QLTY-REST-AUTH-01 |
| permission-service | permissiondb  | Flyway    | validate | var          | Authz scope şeması + `/api/v1/authz/user/{id}/scopes` v1 (TTL 5 dk, 404 AUTHZ-404) | QLTY-DB-PERM-01, QLTY-BE-AUTHZ-SCOPE-01 |
| user-service       | users         | Flyway    | none     | var          | REST/DTO v1 eklendi, legacy uçlar duruyor | QLTY-REST-USER-01 |
| variant-service    | users/…       | Flyway    | none     | var          | DB ayrıştırma planlanıyor                | QLTY-DB-VAR-01 |

### 3.2 Sapmalar (Hedef vs Mevcut)
- DB-topoloji: user/variant aynı DB’yi paylaşıyor; database-per-service hedefi tamamlanmadı (variants DB ayrıştırılacak).  
- Hata modeli: Global handler tamamlandı; legacy endpoint’lerde Response/DTO isimlendirmesi kademeli temizlenecek.  
- Endpoint/DTO: auth-service ve user-service için v1 REST path’leri eklendi, legacy uçlar deprecated olarak tutuluyor.  
- Güvenlik: Keycloak master realm tüm servislerde tek kaynaktır; prod/test’te yalnızca Keycloak JWT geçerlidir, service-token/internal JWT mekanizmaları dev/local ile sınırlandırılmıştır.  
- Secret yönetimi: Vault path’leri (secret/db/*, secret/jwt/auth-service) dolduruldu; servisler fail-fast ile Vault’a bağlı, ancak Vault erişilemezse start alamaz (known constraint).  

### 3.3 Servis Bazlı Güvenlik Notları
- user-service: `ServiceTokenAuthenticationFilter` sadece local/dev; prod/test’te kaldırıldı. `JwtTokenProvider` local/dev ile sınırlı, doğrulama `oauth2ResourceServer(jwt)` üzerinden.  
- permission-service: Internal API key filtresi yalnızca local/dev; prod/test tamamen Keycloak JWT ile erişim.  
- variant-service: Yalnızca Keycloak JWT doğrulaması kullanır; legacy internal JWT yok. Scope setleri permission-service `/api/v1/authz/me` yanıtından okunur, AuthorizationContext cache’lenir; hâlen permission-only çalışır (company/project kolonları eklendiğinde data-scope filtresi TODO olarak açılacak). JWT `scopes` claim’i kullanılmaz. RS256+aud guardrail ve auth-service → servisler arası S2S token akışı değişmedi.  
- auth-service: Legacy service-token stack (ServiceTokenController, ServiceJwksController, AuthService legacy akışı, `serviceauth/*`) local/dev ile sınırlı; prod/test’te yalnızca Keycloak JWT aktif.  
- api-gateway: Prod/test güvenlik zinciri sadece `oauth2ResourceServer(jwt)` + path bazlı authorize; iç auth filtresi yok.  
- Keycloak client/realm mimarisi: `frontend` adlı public client standard flow + silent-check-sso ile çalışır; audience mapper sayesinde access token aud=`["frontend","user-service"]` olur. `user-service`, `permission-service`, `variant-service`, `auth-service` client’ları service-accounts roles *ON* modundadır ve yalnız backend-to-backend çağrılarında kullanılır.  

## 4. Yol Haritası (Backend Backlog)
- Kısa vade (0–3 ay):  
  - [ ] QLTY-DB-AUTH-01: auth-service → Flyway + ddl-auto kapama; dev fallback yetki setini kaldır/flag’le.  
  - [ ] QLTY-DB-PERM-01: permission-service → Flyway + ErrorResponse/global handler; ddl-auto=validate/none.  
  - [ ] QLTY-ERR-01: Tüm servislerde @ControllerAdvice + `ErrorResponse` (STYLE-API-001) ve traceId log korelasyonu.  
  - [ ] SEC-VAULT-FAILOVER-01: Vault fail-fast stratejisi; FE’ye anlamlı hata yüzeyi + monitoring/runbook.  
- Orta vade (3–9 ay):  
  - [ ] QLTY-DB-VAR-01: variant-service’i users DB’den ayır; database-per-service modeline geçiş.  
  - [ ] QLTY-SEC-01: auth/permission güvenlik politikalarını dev/prod ayrımıyla sertleştirme (fallback kapalı, config ile yönetim).  

## 5. Cross-Cutting (Backend)

### Çalışma Ortamı & Docker / Discovery
- Local/dev: Tüm bileşenler docker-compose ile ayağa kalkar:
  - `services/discovery-server` (8761), `services/api-gateway` (8080), `services/user-service` (8090 civarı), `services/variant-service` (8091 civarı), `services/permission-service` (permissiondb), `services/auth-service` (auth_db), Vault (`http://vault:8200` veya `http://localhost:8200`), Keycloak (container’da `http://keycloak:8080`, local’de `http://localhost:8081`), Postgres (postgres-db:5432, kimlik bilgileri Vault’tan gelir).
- Eureka aktifken gateway, servisleri `lb://user-service`, `lb://variant-service`, `lb://permission-service`, `lb://auth-service` rotaları üzerinden keşfeder.
- Local/test profilleri: `docker-compose up` ile discovery + gateway + user/variant/permission/auth + vault + keycloak + postgres kümesi kalkar. JUnit test profilinde H2 + `spring.cloud.vault.enabled=false`; runtime’da Vault zorunlu.

### Güvenlik (Keycloak/JWT)
- Kimlik sağlayıcı: Keycloak (`serban` realm).  
- Tüm servisler (api-gateway, user-service, variant-service, permission-service, auth-service) prod/test profillerinde Spring Security `oauth2ResourceServer(jwt)` ile Keycloak access token doğrulaması yapar.  
- issuer-uri ve jwk-set-uri Keycloak `serban` realm’ini işaret eder (`http://localhost:8081/realms/serban`, `http://localhost:8081/realms/serban/protocol/openid-connect/certs`); konfigürasyonlar Vault üzerinden dağıtılır.  
- Token audience yapısı Keycloak client scope’larıyla yönetilir: `frontend` client’ı varsayılan olarak `audience-user-service`, `audience-permission-service`, `audience-variant-service` scope’larını talep eder ve access token’ın `aud` claim’i hem istemciyi (`frontend`) hem de hedef backend kaynaklarını (user-service/permission-service/variant-service vb.) içerir. Servisler yalnızca kendi adlarını taşıyan audience’ları kabul ettiğinden resource server modeli bozulmadan kalır; gateway ise bu listeyi doğrular.
- Local/dev profillerinde `spring.security.oauth2.resourceserver.jwt.enabled=false` ile JWT doğrulama kapalı tutulur ve permitAll açıktır; SecurityConfigLocal varyantları geliştirme kolaylığı sağlar. Spring Config (application-local.yml/dev.yml) bu bayrağı açıkça taşır.  
- Legacy service-token/internal auth yalnız `local|dev` profillerde kullanılabilir; prod/test için devre dışı bırakıldı.  
- Gateway güvenlik zinciri prod/test’te `oauth2ResourceServer(jwt)` + path bazlı `authorizeHttpRequests` kombinasyonundan oluşur; audience mapper `frontend,user-service` değerlerini taşır.  
- FE çağrıları: Tüm MFE’ler packages/shared-http içindeki ortak HTTP istemcisini (baseURL: `VITE_GATEWAY_URL || http://localhost:8080/api`) kullanır; interceptor Keycloak access token’ını `Authorization: Bearer …` ve `X-Trace-Id` header’ını otomatik ekler. Gateway → discovery → ilgili servis akışı üzerinden `/api/v1/**` uçları hit alır; FE tarafında proxy fallback yoktur.  
- CORS: `api-gateway` dev/local profilinde Spring Cloud Gateway global CORS ayarıyla `http://localhost:3000` ve `http://127.0.0.1:3000` origin’lerini, `authorization,content-type,x-trace-id` header’larını ve tüm HTTP metotlarını (`GET,POST,PUT,DELETE,OPTIONS`) kabul eder; `allowCredentials=true`. Prod/test kurgusunda aynı whitelist ingresse taşınır.  
- REST v1 uçları `/api/v1/**` JWT doğrulaması sonrası erişilebilirdir; `/actuator/**` permitAll kalır.  

### Keycloak Persistence Policy v1.0
- Named volume `backend_keycloak_data` zorunludur; `docker-compose.yml` ve `.env` dosyaları COMPOSE_PROJECT_NAME=serban ile sabitlendikten sonra Keycloak, `/opt/keycloak/data` yolunu bu volume üzerinden kullanır. Volume değişiklikleri (örn. yeni volume oluşturma, mevcut volume’i taşıma) session-log’da ve operasyon runbook’unda kayıt altına alınmak zorundadır.
- `docker compose down -v`, `docker volume prune` gibi volume’i silecek komutlar HARD-RESTRICTED olarak yasaktır ve önerilmeyecektir. Güvenli yeniden başlatma için repo kökündeki `restart.sh` script’i (`docker compose stop && docker compose up -d`) kullanılmalıdır.
- Keycloak realm export mekanizması devreye alınmıştır: konteyner healthcheck’i yeşil olduktan sonra `docker compose exec keycloak /opt/keycloak/bin/kc.sh export --dir /opt/keycloak/data/export --realm serban` komutu çalıştırılır ve çıktı host tarafında `backend/keycloak/exports/` altında saklanır. Prod/test ortamları schedule edilmiş export’larla aynı dizine kopyalanır.
- Dev/test stack artık Keycloak’ı **varsayılan olarak `start-dev` komutuyla** ayağa kaldırır; yani `KEYCLOAK_IMPORT` otomatik tetiklenmez ve UI’dan yaptığınız ayarlar volume üzerinde kalır. Yeni realm’i sıfırdan import etmek için `.env` içine geçici olarak `KEYCLOAK_START_COMMAND="start-dev --import-realm"` ve `KEYCLOAK_IMPORT_FILE=/opt/keycloak/data/import/realm-serban.json` satırlarını ekleyip `docker compose up -d keycloak` çalıştırın ya da doğrudan `docker compose exec keycloak /opt/keycloak/bin/kc.sh import --dir /opt/keycloak/data/import --override true` komutunu verin; işlem biter bitmez bu satırları geri alın.
- Silent-check-sso domain listesi (`http://localhost:3000`, `http://127.0.0.1:3000`, ilgili kurumsal host’lar) Keycloak client tanımında `Valid redirect URIs` ve `Web origins` alanlarına eklenmek zorundadır.
- Compose proje adı `serban` olarak sabitlenmiştir (root `name:` değeri + `.env` içindeki COMPOSE_PROJECT_NAME); farklı proje adları yeni volume yaratacağından yasaktır.
- Risk Analizi: Keycloak volume kaybı = **KRİTİK SEVİYE**. Recovery adımları: (1) `restart.sh` ile stack’i durdurun (`docker compose stop keycloak`). (2) `backend/keycloak/exports/` içindeki en güncel export’u doğrulayın. (3) Volume’i geri yükleyin veya yeni volume oluşturup `docker compose run --rm keycloak /opt/keycloak/bin/kc.sh import --dir /opt/keycloak/data/export --realm serban --override true` komutuyla realm’i içe aktarın. (4) İçe aktarma tamamlanınca `docker compose up -d keycloak` çalıştırın ve silent-check-sso domainlerini doğrulayın. Bu süreçte yapılan tüm adımlar audit log’a yazılmalıdır.

#### Backend JWT Durumu (Kanonik Konfigürasyon)
- `api-gateway`, `user-service`, `variant-service`, `permission-service`, `auth-service` prod/test konfigleri aynı Keycloak issuer/jwks değerlerini kullanır (`http://localhost:8081/realms/serban` ve `.../protocol/openid-connect/certs`).  
- Tüm servisler prod/test profillerinde yalnızca `oauth2ResourceServer().jwt()` zinciriyle Keycloak RS256 access token doğrular; ek filtre yoktur.  
- Legacy “service-token/internal-token” filtreleri tüm servislerde yalnızca `@Profile({"local","dev"})` ile yüklenir; prod/test profillerinde hiçbir servis internal JWT kabul etmez.  
- Local/dev profilleri: `spring.security.oauth2.resourceserver.jwt.enabled=false` ve SecurityConfigLocal sınıfları permitAll davranışı verir (opsiyonel JWT decode).  
- Doğrulama: `mvn -pl <service> -DskipITs=false test` komutları (gateway, user, variant, permission, auth) düzenli olarak yeşil tutulur.  
- Manuel zincir testi:  
  `curl -i -H "Authorization: Bearer <TOKEN>" "http://localhost:8080/api/v1/users?page=1&pageSize=1"`  
  → 200 OK (Keycloak → Gateway → Service zinciri).  
- Vault davranışı: Prod/test profilleri `spring.config.import=vault://secret/...` ile sırları doğrudan Vault’tan çeker ve `fail-fast=true` iken Vault erişilemezse servis başlamaz; local/dev/docker profillerinde Vault import satırı yoktur ve uygulama YAML dosyalarındaki gömülü (örnek) değerlerle veya H2 ile çalışır.  

### API Versioning & Error Model
- `/api/v1/**` path’i tüm servisler için zorunludur; legacy `/api/...` path’leri yalnız geçiş sürecinde `@Deprecated` kalır (Story: `QLTY-API-V1-STANDARDIZATION-01`).  
- Yanıtlar PagedResult (`items`, `total`, `page`, `pageSize`) zarfını kullanır; `sort/search/advancedFilter` whitelist’i STYLE-API-001’de tanımlanır ve aynı parametre isimleri FE/BE arasında zorunludur.  
- `ErrorResponse` (error/message/fieldErrors/meta.traceId) global handler tarafından döndürülür; meta.traceId gateway loglarıyla korele edilir.  

### Observability
- TraceId’li JSON log hedefi; bazı servislerde düz log kısmi. Prometheus/Grafana planlı.

### CI/CD
- Ayrı servis build/test job’ları ve Compose smoke testleri; kalite kapıları (lint/test) kısmi.

### Operasyon / Secret yönetimi (Vault)
- fail-fast zorunlu (`spring.cloud.vault.enabled=true`, `fail-fast=true`).  
- DB sırları KV altında: `secret/db/user-service`, `secret/db/variant-service`, `secret/db/permission-service`, `secret/db/auth-service` (url/user/password).  
- auth-service JWT key’leri `secret/jwt/auth-service` altında; `security.service-jwt.*` / `security.jwt.*` Vault üzerinden okunur.  
- Test profili: Vault kapalı, H2 veya testcontainers; runtime prod/test: Vault erişimi zorunlu.
- **Vault fail-fast & UX:** Prod/stage profillerinde `spring.cloud.vault.fail-fast=true`, `spring.cloud.vault.retry.max-attempts=3`, `initial-interval=2s`. Vault’a ulaşılamazsa servis start’ı “VaultConfigInitializationException → Secrets not obtainable” loguyla kapanır ve orchestrator CrashLoopBackoff’a düşen pod’u yeniden dener. Gateway servis-discovery health check’i kırmızıya düştüğünde `/api/**` çağrılarını 503 `ErrorResponse` ile yanıtlar:
  ```json
  {
    "error": "vault_unavailable",
    "message": "Kimlik altyapısı devrede değil. Bakım tamamlanınca otomatik denenecek.",
    "meta": { "traceId": "<corr-id>", "outageCode": "VAULT_UNAVAILABLE" }
  }
  ```
  Header’lar: `Retry-After: 60` ve `X-Serban-Outage-Code: VAULT_UNAVAILABLE`. Shell login ekranı bu hata kodunu gördüğünde maintenance banner’ını açar; banner metni `frontend/apps/mfe-shell/src/widgets/app-shell/ui/MaintenanceBanner.tsx` içinde tutulur. Runbook: `docs/04-operations/01-runbooks/vault-failfast-fallback.md`.
- **Drill/Test:** `docker compose up -d vault keycloak postgres && docker compose up auth-service` sonrası `docker compose stop vault` ile outage simüle edilebilir; `curl -i http://localhost:8080/api/v1/auth/sessions` isteğinde 503 + `X-Serban-Outage-Code` header’ı beklenir. Drill çıktılarını session-log’a not düşmek zorunlu.

## 6. Change Log
- 2025-12-05 – [AUDIENCE-GUARD-ALIGN] Gateway ve backend servisleri (user/permission/variant/core-data) Keycloak RS256 + audience guardrail ile hizalandı; `/api/v1/**` uçları yalnız geçerli issuer+aud içeren token’ları kabul eder. Gateway’de `/api/v1/companies/**` route eklendi; core-data-service issuer/aud doğrulaması SecurityConfig + testlerle kapatıldı.  
- 2025-11-22 – [QLTY-FE-KEYCLOAK-01-STEP1/STEP2] Gateway ve servis security profilleri: prod/test’te Keycloak JWT zorunlu, dev/local permitAll; Vault fail-fast yapılandırması tamamlandı.  
- 2025-11-22 – Vault devreye alındı, DB/JWT secret’ları secret/db/* ve secret/jwt/auth-service path’lerine taşındı; fail-fast=true.  
- 2025-11-22 – Hedef/Mevcut/Sapma/Yol Haritası formatına geçirildi; Flyway/DB topolojisi tablosu ve sapmalar netleştirildi.  
- 2025-11-23 – [JWT-AUDIENCE-FIX] Keycloak master issuer/JWKS ile aynı kalan JWT doğrulamasına audience (user-service) mapper eklendi; prod/test profilleri audience doğrulaması ile hizalandı, local profiller permitAll kalmaya devam ediyor.  
- 2025-11-23 – [ARCH-FINAL-KEYCLOAK-API] Tüm servislerde (gateway, user, permission, variant, auth) prod/test’te yalnızca Keycloak JWT doğrulaması kaldı; issuer/jwks ortaklaştırıldı, service-token/internal auth dev/local ile sınırlandı, /api/v1/** JWT koruması altında.  
- 2025-12-01 – [QLTY-BE-AUTHZ-SCOPE-01] Permission-service’e scope şeması + `/api/v1/authz/user/{id}/scopes` v1 eklendi (TTL 5 dk, 404 AUTHZ-404); common-auth PermissionCodes ile variant-service global izin kontrolleri hizalandı.  

---

## 7. Mimari Tasarım (Kalıcı İçerik)

Bu bölüm, günlük statü değişse de sabit kalan mimari tasarımı özetler. Güncel durum ve sapmalar için Bölüm 3–4’e bakın.

### 7.1 Sistem Genel Görünüm ve Doküman Haritası
- Bu dosya: Backend mimarisinin kanonik kaydı (servisler, veri, güvenlik, operasyon).
- Frontend mimarisi: `frontend/docs/01-architecture/01-shell/01-frontend-architecture.md`.
- Mimari kararlar: `../03-architecture-governance.md` ve ADR’ler (`../05-governance/05-adr/ADR-*.md`).

### 7.2 Amaç ve Kapsam
Backend ekosisteminin (servisler, altyapı, güvenlik, veri yönetimi ve operasyon) tamamını tek yerde özetleyerek yeni ekip üyelerinin hızlı adaptasyonunu ve mevcut ekibin karar yönetimini sağlar.

### 7.3 Temel İlkeler
- Microservice; servisler bağımsız deploy/scale edilir.
- Cross-cutting konular (auth, permission, audit, secret) ortak stratejilerle çözülür.
- API sözleşmeleri STYLE-API-001’e göre; versiyonlama / hata şeması standart.
- Yerel geliştirme Docker Compose; üretim container orkestratörü uyumlu.

### 7.4 Teknoloji Yığını
- Dil/Framework: Java 21, Spring Boot 3.2.x
- Servis keşfi: Netflix Eureka (`discovery-server`)
- API Geçidi: Spring Cloud Gateway (`api-gateway`)
- Veri Tabanı: Postgres 15 (Compose), database-per-service hedefinde
- İletişim: REST (RestTemplate/Feign), Keycloak JWT temel; legacy servis token’ı yalnızca local/dev’de
- Test: JUnit 5, Spring Boot Test, Rest Assured, Testcontainers
- Güvenlik: JWT (kullanıcı ve servis token’ları), iç API anahtarı, CORS kontrolü

### 7.5 Servis Haritası
| Servis | Görev | Başlıca Bağımlılıklar | Konfigürasyon Kaynağı |
| --- | --- | --- | --- |
| `discovery-server` | Eureka registry | - | `discovery-server/src/main/resources/application.properties` |
| `api-gateway` | Trafik yönlendirme, CORS, servis keşfi | Eureka, tüm uygulama servisleri | `api-gateway/src/main/resources/application*.properties` |
| `auth-service` | Login, kayıt, JWT üretimi, parola reset | `user-service`, `permission-service`, Postgres | `auth-service/src/main/java/com/example/auth/*` |
| `user-service` | Kullanıcı profili, roller, internal yönetim uçları | Postgres, `permission-service` | `user-service/src/main/java/com/example/user/*` |
| `permission-service` | Rol, izin, audit servisleri | Postgres, `auth-service` (servis token) | `permission-service/src/main/java/com/example/permission/*` |
| `variant-service` | AG Grid varyant/preset yönetimi | Postgres | `variant-service/src/main/java/com/example/variant/*` |

### 7.6 İstek Akışları
#### 7.6.1 Kullanıcı Login Akışı
1. İstemci `/api/auth/login` çağrısını `api-gateway` üzerinden yapar.
2. Gateway isteği `auth-service`e iletir.
3. `auth-service` kimlik doğrular, JWT üretir; gerekirse `user-service`/`permission-service` ile profil/izin tamamlar.
4. JWT istemciye döner; sonraki isteklerde `Authorization: Bearer <token>`.

#### 7.6.2 İç Yönetim Akışı (Kullanıcı Aktivasyonu)
1. `mfe-users`, `user-service` üzerindeki `/api/users/internal/{id}/activate` uçlarını çağırır.
2. `user-service`, servis token’ı doğrular; `permission-service` üzerinden yetki kontrolü yapabilir.
3. Audit kayıtları (varsa) `permission-service` içinde tutulur.

#### 7.6.3 Grid Varyant Yönetimi
1. `mfe-users` veya rapor ekranları varyant oluşturma/güncelleme çağrılarını `variant-service`e gönderir.
2. Servis iş kurallarını uygular (tek varsayılan, global varsayılan vb.); FE tarafında optimistic update yapılır.

### 7.7 Veri Yönetimi
- **DB / Migration:** Postgres; hedef database-per-service + Flyway. Mevcut durum için Bölüm 3.1.  
- **Audit:** `permission-service` içinde `PermissionAuditEvent` modeli ile izin değişiklikleri kayıt altına alınır.
- **Token Deposu:** `auth-service` parola reset / email doğrulama token’larını JPA repository’lerinde saklar.
- **Gizli Bilgiler:** Vault KV zorunlu (fail-fast); path’ler `secret/db/<service>` (url/user/password) ve `secret/jwt/auth-service` (privateKey/publicKey).  

### 7.8 Güvenlik Katmanı
- JWT kullanıcı token’ı: Keycloak (`serban` realm) üretir; tüm servisler/gateway prod/test’te `oauth2ResourceServer(jwt)` ile issuer=`http://localhost:8081/realms/serban`, jwk-set-uri=`http://localhost:8081/realms/serban/protocol/openid-connect/certs` doğrular.  
- Servis token’ı: Legacy mekanizma prod/test’te devre dışı; yalnızca local/dev’de (varsa) geliştirme amaçlı kullanılabilir.  
- API anahtarı: `permission-service` kritik internal uçlar için `X-Internal-Api-Key` filtresi local/dev ile sınırlıdır.  
- CORS: `api-gateway` global ayarlarla MFE domain’lerine izin verir.  
- TLS: Dev’de HTTP; prod planında gateway/ingress üzerinden TLS terminasyonu.

### 7.9 Operasyon ve Gözlemlenebilirlik
- Health Check: Tüm servisler `/actuator/health`; Compose healthcheck tanımlı.
- Logging: Spring Boot default; hedef JSON + traceId (STYLE-API-001 meta.traceId).
- Monitoring/Alerting: Prometheus/Grafana planlı (bkz. observability dokümanları).
- Runbook: Vault, JWT, kill-switch, secret rotasyonu vb. ilgili markdown’larda.

### 7.10 Dağıtım ve Ortamlar
- Yerel: `docker compose up --build` ile tüm servisler (Postgres dahil).
- Geliştirme/Test: Servisler `mvn spring-boot:run`; `SPRING_PROFILES_ACTIVE=local`.
- Üretim Planı: Container orkestrasyonu (Kubernetes vb.), config server/Vault, ingress/gateway TLS.
- CI/CD: Öneri olarak servis başına bağımsız build/test job’ları ve Compose smoke test adımları.

### 7.11 Mevcut Durum ve Açık İşler
- Mevcut sapmalar ve backlog için Bölüm 3–4’e bakın; burada yalnız hatırlatma: Flyway ve ErrorResponse standardı tam yaygın değil, database-per-service geçişi sürüyor.

### 7.12 Referanslar
- API sözleşmeleri: `docs/03-delivery/api/*.md`
- Stil rehberleri: `docs/00-handbook/STYLE-BE-001.md`, `docs/00-handbook/STYLE-API-001.md`
- Governance: `docs/05-governance/PROJECT_FLOW.md`, ADR’ler `docs/05-governance/05-adr/ADR-*.md`
- Operasyon/observability: ilgili runbook ve güvenlik dokümanları (`docs/vault-*.md`, `docs/observability-security-tests.md`)

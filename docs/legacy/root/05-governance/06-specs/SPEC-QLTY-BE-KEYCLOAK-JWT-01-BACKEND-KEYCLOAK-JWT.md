# SPEC-QLTY-BE-KEYCLOAK-JWT-01-BACKEND-KEYCLOAK-JWT
**Başlık:** Backend Keycloak JWT Sertleştirmesi & Provisioning  
**Versiyon:** v1.0  
**Tarih:** 2025-11-30  

**İlgili Dokümanlar:**  
- EPIC: docs/05-governance/01-epics/QLTY_Security-Hardening.md  
- ADR: docs/05-governance/05-adr/ADR-003-auth-and-broadcast-channel.md  
- ADR: docs/05-governance/05-adr/ADR-010-security-pipeline.md  
- ACCEPTANCE: docs/05-governance/07-acceptance/QLTY-BE-KEYCLOAK-JWT-01.acceptance.md  
- STORY: docs/05-governance/02-stories/QLTY-BE-KEYCLOAK-JWT-01.md  
- STYLE GUIDE: docs/00-handbook/STYLE-BE-001.md, docs/00-handbook/STYLE-API-001.md  

**Etkilenen Modüller / Servisler:**  

| Modül/Servis    | Açıklama / Sorumluluk                                      | İlgili ADR |
|-----------------|-------------------------------------------------------------|------------|
| api-gateway     | Prod/test’te yalnız Keycloak RS256 JWT doğrular             | ADR-010    |
| auth-service    | Dev-only legacy filtreler, prod/test’te yalnız JWT          | ADR-010    |
| user-service    | Service token profilleri + Keycloak provisioning endpoint   | ADR-003    |
| permission-service | Internal API key dev-only, JWT zorunlu                   | ADR-010    |
| variant-service | Keycloak issuer/jwks tekilleştirmesi                        | ADR-010    |
| ops-ci          | Guardrail testleri, runbook & script yönetimi               | ADR-010    |

---

# 1. Amaç (Purpose)
Prod/test ortamlarında tüm backend servislerinin Keycloak RS256 JWT zinciri üzerinde birleşmesini, dev/local profillerine izolasyon yapılmasını ve Keycloak kullanıcılarının user-service veritabanına otomatik aktarılmasını (provisioning API + script) tanımlar.

# 2. Kapsam (Scope)

### Kapsam içi
- Spring Security konfigürasyonlarının profil bazlı ayrıştırılması.  
- Vault/Keycloak konfigürasyonu ve environment değişkenleri.  
- `POST /api/v1/users/internal/provision` API’si ve yetkili servis token akışı.  
- Keycloak admin flow script’i (`provision-user.sh`) ve periyodik senkron script’i (`sync-keycloak-users.sh`).  
- Observability (log/metric) ve runbook güncellemeleri.

### Kapsam dışı
- Keycloak realm içi MFA, grup yönetimi veya UI değişiklikleri.  
- Frontend/shared-http değişiklikleri (QLTY-FE-KEYCLOAK-01 kapsamında).  
- Prod Keycloak cluster bakım işleri.

# 3. Tanımlar (Definitions)
- **Permitted Profiles:** `local`, `dev` – permitAll + legacy filtreler bu profillerle sınırlı.  
- **Service Token:** Keycloak “client credentials” akışıyla alınan, `svc` claim’i bulunan JWT.  
- **Provisioning:** Keycloak’ta bulunan kullanıcı için user-service veritabanında satır oluşturma/güncelleme işlemi.  
- **Legacy Filter:** `ServiceTokenAuthenticationFilter`, `InternalApiKeyAuthFilter`, vb. dev yardımcı filtreler.

# 4. Kullanıcı Senaryoları (User Flows)
1. **Prod Request → Valid JWT:** Gateway request’i doğrular, traceId/log formatı korunarak ilgili servise yönlendirir, servis JWT audience/issuer doğrular ve 200 döner.  
2. **Prod Request → Legacy Token:** PermitAll dev filtresi yüklenmediği için 401 döner, log’da `legacy_token_blocked` etiketi bulunur.  
3. **Keycloak Admin:** Yeni kullanıcı oluşturur → CLI `provision-user.sh` script’i çağrılır → user-service provisioning endpoint’i kullanıcıyı kayıt eder.  
4. **Periyodik Senkron:** `sync-keycloak-users.sh` cron ile tüm Keycloak kullanıcılarını tarar, user-service’te eksik olanları (veya metadata güncellemesi gerekenleri) provisioning API’siyle eşitler.

# 5. Fonksiyonel Gereksinimler (Functional Requirements)
- **FR-BEKJ-01:** Prod/test profillerinde tüm servisler `spring.security.oauth2.resourceserver.jwt` konfigürasyonuyla Keycloak RS256 tokenlarını doğrulamak zorunda.  
- **FR-BEKJ-02:** `ServiceTokenAuthenticationFilter`, `InternalApiKeyAuthFilter` ve admin fallback’leri yalnız `local|dev` profillerinde etkin olmalı.  
- **FR-BEKJ-03:** `POST /api/v1/users/internal/provision` endpoint’i service token yetkisiyle kullanıcı kayıt/güncelleme işlemi yapmalı; idempotent olmalı.  
- **FR-BEKJ-04:** CLI `scripts/keycloak/provision-user.sh` manuel provisioning yapmalı; `sync-keycloak-users.sh` Keycloak Admin API’den kullanıcıları çekip provisioning endpoint’ine iletmeli.  
- **FR-BEKJ-05:** Vault path’leri ve Keycloak config değerleri tek kaynak olarak `docs/01-architecture/01-system/01-backend-architecture.md` + runbook’ta belgelenmeli.  
- **FR-BEKJ-06:** Her servis için security smoke testi (geçerli JWT → 200, hatalı JWT → 401/403) run logu evidence olarak saklanmalı.

# 6. İş Kuralları (Business Rules)
- **BR-BEKJ-01:** Prod/test profilinde service token ile gelen request’ler `svc` claim’ini doğrulamak ve `permissions` claim’inde `users:internal` vb. izni aramak zorunda.  
- **BR-BEKJ-02:** Dev/local profillerinde permitAll aktif olabilir; ancak log’larda dev profili açıkça belirtilmeli.  
- **BR-BEKJ-03:** Provisioning API email’i unique kabul eder, duplicate email 409 döndürür, user-service DB’yi authoritative tutar.  
- **BR-BEKJ-04:** Provisioning request’i gelemeyen kullanıcılar `sync-keycloak-users.sh` tarafından en geç 1 saat içinde user-service’e aktarılmalıdır (cron).  
- **BR-BEKJ-05:** Vault erişimi başarısız olursa servis fail-fast etmelidir; fallback credential kullanılmaz.

# 7. Veri Modeli (Data Model)
## 7.1 Veritabanı modeli
`users` tablosu mevcut alanları korur; provisioning API’si var olan `users` tablosuna yazar. Ek tablo gerekmez.

## 7.2 Request/Response DTO
```json
{
  "email": "admin@example.com",
  "name": "Console Admin",
  "role": "ADMIN",
  "enabled": true,
  "sessionTimeoutMinutes": 15
}
```

# 8. API Tanımı (API Spec)

## 8.1 Endpoint
- Method: `POST`
- Path: `/api/v1/users/internal/provision`
- Auth: Service token (`Authorization: Bearer <token>`), `PERM_users:internal` yetkisi zorunlu
- Content-Type: `application/json`

## 8.2 Request Body
| Alan | Tip | Zorunlu | Açıklama |
|------|-----|---------|----------|
| email | string | Evet | Keycloak kullanıcı email’i |
| name | string | Evet | Ad + soyad |
| role | string | Hayır (varsayılan USER) | user-service rolü |
| enabled | boolean | Hayır (varsayılan true) | Hesap aktiflik bilgisi |
| sessionTimeoutMinutes | integer | Hayır (varsayılan 15) | Oturum süresi |

## 8.3 Successful Response
```json
{
  "id": 481,
  "email": "admin@example.com",
  "role": "ADMIN",
  "enabled": true,
  "sessionTimeoutMinutes": 15
}
```

## 8.4 Error Responses
| HTTP | Kod | Açıklama |
|------|-----|----------|
| 400  | invalid_payload | Validation hatası |
| 401  | unauthorized | Service token eksik/yanlış |
| 403  | insufficient_permissions | `PERM_users:internal` yok |
| 409  | duplicate_email | email başka kullanıcıya ait |

# 9. Validasyon Kuralları
- `email`: RFC 5322 uyumlu format, 320 karakterden uzun olamaz.  
- `name`: 2–256 karakter; trim sonrası boş olamaz.  
- `role`: `USER`, `ADMIN`, `AUDITOR` vb. uppercase değerler; boşsa `USER`.  
- `sessionTimeoutMinutes`: 1 ≤ değer ≤ `maxSessionTimeoutMinutes` (JWT expiration’dan türetilir).  
- `enabled`: boşsa `true`; dev profile’da `false` kabul edilir.

# 10. Hata Kodları
| Kod | HTTP | Açıklama |
|-----|------|----------|
| invalid_payload | 400 | Validation hatası |
| unauthorized | 401 | Service token eksik/yanlış |
| insufficient_permissions | 403 | Service token izni yok |
| duplicate_email | 409 | Email zaten kayıtlı |

# 11. Non-Fonksiyonel Gereksinimler (NFR)
- **Security:** JWT doğrulaması, service token izin kontrolü, loglarda token maskesi.  
- **Performance:** Provisioning API single-row upsert; 95p latency < 150ms.  
- **Reliability:** Scripts idempotent olmalı; network hatası durumunda tekrar çalıştırılabilir.  
- **Scalability:** Provisioning endpoint stateless; yatay ölçeklenebilir.  
- **Compliance:** Audit log’larında provisioning event’i `user_service.provision` adıyla traceId + userId ile saklanır.

# 12. Observability & Monitoring
- API success/failure metriği: `user_provision_total{result="success|error"}`  
- Log alanları: `traceId`, `email`, `serviceId`, `result`.  
- Grafana dashboard’a Keycloak sync job healthcheck’i eklenir.  
- Alert: 30 dk içinde provisioning hatası %5’i geçerse uyarı.

# 13. Dağıtım ve Geri Alma
- Feature flag gerekmez; endpoint prod’a deploy edildiğinde dev script’ler anında kullanılabilir.  
- Geri alma: Endpoint’i dev-only profile’a almak ve scripts/scheduler’ı devre dışı bırakmak yeterli.  
- Ops adımları `docs/02-security/01-identity/10-keycloak-user-provisioning.md` ve `docs/04-operations/01-runbooks/keycloak-admin-guide.md` içinde tariflidir.

# 14. Açık Sorular / Bilinen Riskler
- Keycloak kullanıcı email’i olmayan hesaplar (ör. hizmet hesabı) provisioning sırasında atlanır; future scope.  
- Eski service token tüketicileri (ör. cron job) prod’da JWT’ye geçmedi ise request’leri 401 alacaktır; rollout planı Acceptance’da takip edilir.

# 📊 PROJECT FLOW BOARD  
Güncelleme: 2025-12-05  
Toplam Story: 32  
Devam Eden: 4  
Tamamlanan: 20  

---

# 🟩 STORY DURUM ÖZETİ (ASCII – %100 HİZALI TABLO)

```text
+------------+----------------------------------------------+----------------+-----------------------+
| Story ID   | Başlık                                      | Story Priority | Son Durum             |
+------------+----------------------------------------------+----------------+-----------------------+
| E01-S05    | Shell Login & Register (Tailwind + i18n)    | 110            | ✔ Tamamlandı          |
| E01-S10    | Auth & BroadcastChannel Full MFE Sync       | 120            | ✔ Tamamlandı          |
| E01-S90    | Identity Support & Small Tasks              | 900            | ✔ Tamamlandı          |
| E02-S01    | AG Grid Standardı & Deneyim Bütçeleri       | 210            | ✔ Tamamlandı          |
| E02-S02    | Grid Mimarisi (UI Kit EntityGridTemplate)   | 220            | ✔ Tamamlandı          |
| E03-S01    | Theme & Layout System v1.0                  | 310            | ✔ Tamamlandı          |
| E03-S01-S01| Accent Primary & Focus Var Yayma            | 312            | 🔧 Devam ediyor        |
| E03-S01-S01.1 | Accent / HC Görsel Tuning                 | 313            | ⏳ Planlandı           |
| E03-S02    | Theme Runtime Integration                   | 315            | ✔ Tamamlandı          |
| E03-S03    | Theme Overlay & Grid Tone                   | 320            | ✔ Tamamlandı          |
| E03-S04    | Header Navigasyon & Overflow                | 320            | ⏳ Planlandı           |
| E03-S05    | Theme Personalization & Custom Themes       | 130            | 🔧 Devam ediyor        |
| E04-S01    | Manifest & Sözleşme Platformu v1.0          | 410            | ✔ Tamamlandı          |
| E05-S01    | Canary, Flag & DR Guardrail’leri v1.0       | 510            | ⏳ Planlandı           |
| E06-S01    | Telemetry & Observability Korelasyonu v1.0  | 610            | 🔧 Devam ediyor       |
| E07-S01    | i18n & Erişilebilirlik Süreçleri v1.0       | 710            | 🔧 Devam ediyor       | Pseudo/fallback + missing-key telemetry + page/action/mutation telemetry + Playwright/axe a11y smoke lokal hazır; tema/kontrast QA-02 ve axe CI entegrasyonu bir sonraki fazda tamamlanacak. |
| E08-S01    | Permission Registry v1.0                    | 810            | 🔎 Review              |
| QLTY-01-S1 | Backend'de STYLE-BE-001 yaygınlaştırma      | 050            | ✔ Tamamlandı          |
| QLTY-01-S2 | Frontend'de STYLE-FE-001 yaygınlaştırma     | 055            | 🔧 Devam ediyor       |
| QLTY-01-S3 | API dokümanlarını STYLE-API-001 ile hizalama| 060            | ✔ Tamamlandı          |
| QLTY-01-S4 | Quality Handbook + PR checklist adoption    | 065            | 🔧 Devam ediyor       |
| QLTY-REST-AUTH-01 | Auth REST/DTO v1 geçişi              | 070            | ✔ Tamamlandı          |
| QLTY-BE-AUTHZ-SCOPE-01 | Permission-service scope’lu yetkilendirme | 999            | 🔧 Devam ediyor       |
| QLTY-REST-USER-01 | User REST/DTO v1 geçişi              | 075            | ✔ Tamamlandı          |
| QLTY-MF-ROUTER-01 | MF Router Shared & Version Pin       | 080            | ✔ Tamamlandı          |
| QLTY-FE-KEYCLOAK-01 | Frontend Keycloak/OIDC entegrasyonu | 081            | ✔ Tamamlandı          |
| QLTY-MF-UIKIT-01 | UI Kit Model Unification              | 082            | ✔ Tamamlandı          |
| QLTY-FE-SHARED-HTTP-01 | Ortak HTTP istemcisi (@mfe/shared-http) | 083            | ✔ Tamamlandı          |
| QLTY-FE-V1-ENDPOINT-NORMALIZATION | FE v1 endpoint normalizasyonu | 084            | ✔ Tamamlandı          |
| QLTY-FE-GATEWAY-ROUTING | FE gateway routing (baseURL tekilliği) | 085            | ✔ Tamamlandı          |
| QLTY-BE-KEYCLOAK-JWT-01 | Backend Keycloak JWT güvenliği   | 086            | ✔ Tamamlandı          |
| QLTY-API-V1-STANDARDIZATION-01 | /api/v1 standardizasyonu       | 087            | ✔ Tamamlandı          |
| QLTY-FE-SPA-LOGIN-01 | SPA Login & silent-check-sso        | 088            | ✔ Tamamlandı          |
| QLTY-FE-SHELL-AUTH-01 | Shell Auth Merkezi + Shared HTTP Entegrasyonu | 089            | ✔ Tamamlandı          |
| SEC-VAULT-FAILOVER-01 | Vault Fail-Fast Fallback Strategy| 950            | ✔ Tamamlandı          |
| QLTY-BE-CORE-DATA-01 | Company Master Data (core-data-service) | 980            | ✔ Tamamlandı          |
| QLTY-BE-AUTHZ-SCOPE-03 | User-Service company scope filtresi | 995            | ⏳ Planlandı           |
| QLTY-BE-AUTHZ-SCOPE-03-PROJECT | Proje/şantiye PROJECT scope filtresi | 994 | ⏳ Planlandı |
| QLTY-FE-VERSIONS-01 | FE Version Pinning & MF Audit      | 084            | ⏳ Planlandı           |
+------------+----------------------------------------------+----------------+-----------------------+
```

Not:
- “Son Durum” sütunu `docs/05-governance/02-stories/*.md` içindeki `Durum:` alanından türetilir.
- QLTY-MF-UIKIT-01 kapandı: UI Kit paket modeli (packages/ui-kit) tüm MFE’lerde tek resmi kaynak ve `mf_ui_kit` remote devreden çıkarıldı.  
- QLTY-FE-SHARED-HTTP-01, QLTY-FE-V1-ENDPOINT-NORMALIZATION, QLTY-FE-GATEWAY-ROUTING tamamlandı: @mfe/shared-http ile ortak HTTP katmanı, `/v1` prefix normalizasyonu ve gateway baseURL tekilliği prod/dev’de devreye alındı.  
- QLTY-API-V1-STANDARDIZATION-01 ve QLTY-FE-SPA-LOGIN-01 tamamlandı: `/api/v1/**` zarf standardı ve SPA login + silent-check-sso akışı mimari belgelerle senkron hale getirildi; Keycloak audience mapper (frontend,user-service) ve shared-http interceptor davranışı ARCH-STATUS’ta kayıt altına alındı.  
- QLTY-FE-KEYCLOAK-01 tamamlandı; QLTY-BE-KEYCLOAK-JWT-01 Story’si backend Keycloak RS256 guardrail’leri için hâlâ plan/aşamasında.  
- QLTY-BE-KEYCLOAK-JWT-01: User-Service provisioning + API Gateway/Auth/User/Permission/Variant güvenlik checklist’leri için `mvn -pl <service> test` smoke çıktıları ve Vault/audience dokümantasyonu evidence dosyasına işlendi; acceptance dosyası `status: done`.
- QLTY-FE-SHELL-AUTH-01 tamamlandı: Keycloak login/init/logout yalnız shell’de tutuldu, token yalnız bellek içi store’da saklanıp BroadcastChannel ile dağıtılıyor ve tüm remote MFE çağrıları @mfe/shared-http aracılığıyla gateway → servis zincirine yönlendiriliyor; dokümantasyon FRONTEND/BACKEND ARCH-STATUS + session-log ile senkron.  
- QLTY-FE-SHARED-HTTP-01 tamamlandı: `packages/shared-http` tek HTTP istemcisi olarak publish edildi, request interceptor token + `X-Trace-Id` ekliyor, response interceptor 401’leri shell’deki `/login?redirect=...` akışına yönlendiriyor; audit/reporting/users/ui-kit servis katmanları ve shell auth/telemetry bu katmana taşındı, MF shared listelerinde `@mfe/shared-http` singleton olarak paylaşılıyor ve shell auth state’i BroadcastChannel ile gerçek zamanlı dağıtılıyor (BroadcastChannel desteklenmiyorsa yalnız logout sinyali için storage event fallback kullanılır).  
- Keycloak Persistence Policy v1.0 bu sprintte devreye alındı: `backend_keycloak_data` volume’u zorunlu, `docker compose down -v` / `docker volume prune` yasak, `backend/keycloak/exports/` altına realm export alınması ve Compose proje adının `serban` olarak kilitlenmesi ARCH-STATUS ile FRONTEND-ARCH-STATUS’ta dokümante edildi. Volume değişikliği veya recovery işlemleri session-log/audit kayıtlarına işlenir.  
- 2025-12-02: Keycloak permissions mapper’ları PermissionCodes ile hizalandı; admin1@example.com token’ında permissions + aud/iss doğrulaması yapıldı. Variant-service bu iterasyonda scope kısıtını devre dışı bırakarak yalnız permission kontrolüyle çalışıyor; ayrıntı session-log’da.
- 2025-12-03: SCOPE-03 (user-service company scope) açıldı: `users.company_id` kolonu eklendi, AuthorizationContext ile company scope filtresi admin dışındaki kullanıcılar için etkin; boş scope → boş sonuç, superAdmin → bypass.
- 2025-12-03: SCOPE-03-PROJECT planlandı: project-service listeleme uçlarında PROJECT scope filtresi uygulanacak; superAdmin bypass, scope boşsa boş liste. Variant/preset global kalacak.

---

# 🟦 AKTİF İŞLER (In Progress)

- E02-S01 – AG Grid Standardı & Deneyim Bütçeleri  
- QLTY-01-S2 – Frontend'de STYLE-FE-001 yaygınlaştırma  
- QLTY-01-S4 – Quality Handbook + PR checklist adoption  
- QLTY-BE-AUTHZ-SCOPE-01 – Permission-service scope’lu yetkilendirme  
- QLTY-BE-AUTHZ-SCOPE-02 – Scope’lu authz’nin access/audit/user servislerine yaygınlaştırılması  

---

# 🟨 REVIEW’DE BEKLEYENLER

- E01-S05 – Shell Login & Register (Tailwind + i18n)  
- E08-S01 – Permission Registry v1.0  

---

# 🟥 BLOKER (ENGELLENENLER)

- Global bloklayıcı yok.  
- Story bazlı riskler ilgili Story dokümanlarında ve acceptance dosyalarında tutulur.  
- Harici sistem bağımlılıkları (TMS, OTEL backend, Grafana Prod erişimi vb.) için detaylar ilgili runbook ve ADR’lerde belgelenmiştir.

---

# 🟩 TAMAMLANANLAR

- E01-S10 – Auth & BroadcastChannel Full MFE Sync  
  - Story: `docs/05-governance/02-stories/E01-S10-Auth-BroadcastChannel-Full-MFE-Sync.md`  
  - Acceptance: `docs/05-governance/07-acceptance/E01-S10-Auth-BroadcastChannel-Full-MFE-Sync.acceptance.md`
  - Not: 2025-11-29 itibarıyla Keycloak `serban` realm’ine user/permission/variant audience scope’ları eklendi; frontend client bu scope’ları varsayılan olarak talep ediyor ve backend servisleri SECURITY_JWT_AUDIENCE gevşetmesi olmadan yalnız kendi adlarını doğruluyor.

- E01-S90 – Identity Support & Small Tasks  
  - Story: `docs/05-governance/02-stories/E01-S90-Identity-Support.md`  
  - Acceptance: `docs/05-governance/07-acceptance/E01-S90-Identity-Support.acceptance.md`

- E03-S02 – Theme Runtime Integration  
  - Story: `docs/05-governance/02-stories/E03-S02-Theme-Runtime-Integration.md`  
  - Acceptance: `docs/05-governance/07-acceptance/E03-S02-Theme-Runtime-Integration.acceptance.md`

- QLTY-01-S1 – Backend'de STYLE-BE-001 yaygınlaştırma  
  - Story: `docs/05-governance/02-stories/QLTY-01-S1-Backend-Style-Guide-Rollout.md`  
  - Acceptance: `docs/05-governance/07-acceptance/QLTY-01-S1-Backend-Style-Guide-Rollout.acceptance.md`

- E02-S02 – Grid Mimarisi (UI Kit EntityGridTemplate + SSRM)  
  - Story: `docs/05-governance/02-stories/E02-S02-Grid-UI-Kit-SSRM.md`  
  - Acceptance: `docs/05-governance/07-acceptance/E02-S02-Grid-UI-Kit-SSRM.acceptance.md`

- QLTY-01-S3 – API dokümanlarını STYLE-API-001 ile hizalama  
  - Story: `docs/05-governance/02-stories/QLTY-01-S3-API-Style-Guide-Rollout.md`  
  - Acceptance: `docs/05-governance/07-acceptance/QLTY-01-S3-API-Style-Guide-Rollout.acceptance.md`

- E03-S01 – Theme & Layout System v1.0  
  - Story: `docs/05-governance/02-stories/E03-S01-Theme-Layout-System.md`  
  - Acceptance: `docs/05-governance/07-acceptance/E03-S01-Theme-Layout-System.acceptance.md`
  - Not (2025-12-06): Smoke/SSOT kısmi: preset/persist OK; eksikler: density/radius/elevation görsel bağlama, overlay slider’ın gerçek overlay’e uygulanması, accent/HC kontrast ve grid/toolbar CTA accent etkisinin güçlendirilmesi.

- QLTY-REST-AUTH-01 – Auth REST/DTO v1 geçişi  
  - Story: `docs/05-governance/02-stories/QLTY-REST-AUTH-01.md`  
  - Acceptance: `docs/05-governance/07-acceptance/QLTY-REST-AUTH-01.acceptance.md`
  - Not: `/api/v1/auth` sessions/registrations/password-resets/email-verifications uçları devrede; MockMvc (`AuthControllerV1Test`) + `mvn -pl auth-service test` 2025-11-29 koşuları yeşil, legacy `/api/auth/*` uçları deprecated olarak belgeli.

- QLTY-REST-USER-01 – User REST/DTO v1 geçişi  
  - Story: `docs/05-governance/02-stories/QLTY-REST-USER-01.md`  
  - Acceptance: `docs/05-governance/07-acceptance/QLTY-REST-USER-01.acceptance.md`
  - Not: `/api/v1/users` liste/detay/by-email/activation uçları PagedResult + advancedFilter whitelist ile yayınlandı; `UserControllerV1Test` + `mvn -pl user-service test` JWT doğrulamasıyla geçti, legacy `/api/users/*` uçları deprecated etiketiyle açık.

- QLTY-FE-KEYCLOAK-01 – Frontend Keycloak/OIDC entegrasyonu  
  - Story: `docs/05-governance/02-stories/QLTY-FE-KEYCLOAK-01-Frontend-Keycloak-OIDC.md`  
  - Acceptance: `docs/05-governance/07-acceptance/QLTY-FE-KEYCLOAK-01.acceptance.md`  
  - Not: Shell Keycloak client konfigürasyonu tek `auth-config` modülüne taşındı; `@mfe/shared-http` Authorization + `X-Trace-Id` interceptor’ları gateway loglarıyla doğrulandı, permitAll modu dokümante edildi ve login/logout ekran görüntüleri evidence klasörüne eklendi.

Tamamlanan Story’ler acceptance checklist’leri kapandıktan sonra burada listelenir.

---

# 🔗 STORY LİNKLERİ

- E01-S05 – Shell Login & Register (Tailwind + i18n)  
  - Story: `docs/05-governance/02-stories/E01-S05-Login.md`  
  - Acceptance: `docs/05-governance/07-acceptance/E01-S05-Login.acceptance.md`

- E01-S10 – Auth & BroadcastChannel Full MFE Sync  
  - Story: `docs/05-governance/02-stories/E01-S10-Auth-BroadcastChannel-Full-MFE-Sync.md`  
  - Acceptance: `docs/05-governance/07-acceptance/E01-S10-Auth-BroadcastChannel-Full-MFE-Sync.acceptance.md`

- E03-S01-S01 – Accent Primary & Focus Var Yayma  
  - Story: `docs/05-governance/02-stories/E03-S01-S01-Accent-Primary-Focus.md`  
  - Acceptance: `docs/05-governance/07-acceptance/E03-S01-Theme-Layout-System.acceptance.md`
  - Not (2025-12-06): Teknik wiring tamam, accent var’ları çalışıyor; UX tuning gerek (accent etkisi zayıf, HC kontrast artırılmalı); overlay/density/radius/elevation eksenleri UI’da uygulanmadı, takip dilimleri (E03-S01-S01.1 vb.) açılacak.

- E03-S01-S01.1 – Accent / HC Görsel Tuning  
  - Story: `docs/05-governance/02-stories/E03-S01-S01-1-Accent-HC-Tuning.md`  
  - Acceptance: `docs/05-governance/07-acceptance/E03-S01-Theme-Layout-System.acceptance.md`

- E01-S90 – Identity Support & Small Tasks  
  - Story: `docs/05-governance/02-stories/E01-S90-Identity-Support.md`  
  - Acceptance: `docs/05-governance/07-acceptance/E01-S90-Identity-Support.acceptance.md`

- E02-S01 – AG Grid Standardı & Deneyim Bütçeleri  
  - Story: `docs/05-governance/02-stories/E02-S01-AG-Grid-Standard.md`  
  - Acceptance: `docs/05-governance/07-acceptance/E02-S01-AG-Grid-Standard.acceptance.md`

- E02-S02 – Grid Mimarisi (UI Kit EntityGridTemplate + SSRM)  
  - Story: `docs/05-governance/02-stories/E02-S02-Grid-UI-Kit-SSRM.md`  
  - Acceptance: `docs/05-governance/07-acceptance/E02-S02-Grid-UI-Kit-SSRM.acceptance.md`

- E03-S01 – Theme & Layout System v1.0  
  - Story: `docs/05-governance/02-stories/E03-S01-Theme-Layout-System.md`  
  - Acceptance: `docs/05-governance/07-acceptance/E03-S01-Theme-Layout-System.acceptance.md`

- E03-S03 – Theme Overlay & Grid Tone  
  - Story: `docs/05-governance/02-stories/E03-S03-Theme-Overlay-And-Grid-Tone.md`  
  - Acceptance: `docs/05-governance/07-acceptance/E03-S03-Theme-Overlay-And-Grid-Tone.acceptance.md`

- E03-S04 – Header Navigasyon & Overflow  
  - Story: `docs/05-governance/02-stories/E03-S04-Header-Nav-Overflow.md`  
  - Acceptance: `docs/05-governance/07-acceptance/E03-S04-Header-Nav-Overflow.acceptance.md`

- E03-S01-S01 – Accent Primary & Focus Var Yayma  
  - Story: `docs/05-governance/02-stories/E03-S01-S01-Accent-Primary-Focus.md`  
  - Acceptance: `docs/05-governance/07-acceptance/E03-S01-Theme-Layout-System.acceptance.md`

- E04-S01 – Manifest & Sözleşme Platformu v1.0  
  - Story: `docs/05-governance/02-stories/E04-S01-Manifest-Platform-v1.md`  
  - Acceptance: `docs/05-governance/07-acceptance/E04-S01-Manifest-Platform-v1.acceptance.md`

- E05-S01 – Canary, Flag & DR Guardrail’leri v1.0  
  - Story: `docs/05-governance/02-stories/E05-S01-Release-Safety-and-DR.md`  
  - Acceptance: `docs/05-governance/07-acceptance/E05-S01-Release-Safety-and-DR.acceptance.md`

- E06-S01 – Telemetry & Observability Korelasyonu v1.0  
  - Story: `docs/05-governance/02-stories/E06-S01-Telemetry-and-Observability.md`  
  - Acceptance: `docs/05-governance/07-acceptance/E06-S01-Telemetry-and-Observability.acceptance.md`

- E07-S01 – i18n & Erişilebilirlik Süreçleri v1.0  
  - Story: `docs/05-governance/02-stories/E07-S01-Globalization-and-Accessibility.md`  
  - Acceptance: `docs/05-governance/07-acceptance/E07-S01-Globalization-and-Accessibility.acceptance.md`

- E08-S01 – Permission Registry v1.0  
  - Story: `docs/05-governance/02-stories/E08-S01-Permission-Registry.md`  
  - Acceptance: `docs/05-governance/07-acceptance/E08-S01-Permission-Registry.acceptance.md`

- QLTY-01-S1 – Backend'de STYLE-BE-001 yaygınlaştırma  
  - Story: `docs/05-governance/02-stories/QLTY-01-S1-Backend-Style-Guide-Rollout.md`  
  - Acceptance: `docs/05-governance/07-acceptance/QLTY-01-S1-Backend-Style-Guide-Rollout.acceptance.md`

- QLTY-01-S2 – Frontend'de STYLE-FE-001 yaygınlaştırma  
  - Story: `docs/05-governance/02-stories/QLTY-01-S2-Frontend-Style-Guide-Rollout.md`  
  - Acceptance: `docs/05-governance/07-acceptance/QLTY-01-S2-Frontend-Style-Guide-Rollout.acceptance.md`

- QLTY-01-S3 – API dokümanlarını STYLE-API-001 ile hizalama  
  - Story: `docs/05-governance/02-stories/QLTY-01-S3-API-Style-Guide-Rollout.md`  
  - Acceptance: `docs/05-governance/07-acceptance/QLTY-01-S3-API-Style-Guide-Rollout.acceptance.md`

- QLTY-01-S4 – Quality Index + PR checklist eğitim/duyuru  
  - Story: `docs/05-governance/02-stories/QLTY-01-S4-Quality-Handbook-Adoption.md`  
  - Acceptance: `docs/05-governance/07-acceptance/QLTY-01-S4-Quality-Handbook-Adoption.acceptance.md`

- QLTY-BE-AUTHZ-SCOPE-01 – Permission-service scope’lu yetkilendirme  
  - Story: `docs/05-governance/02-stories/QLTY-BE-AUTHZ-SCOPE-01.md`  
  - Spec: `docs/05-governance/06-specs/SPEC-QLTY-BE-AUTHZ-SCOPE-01-AUTHZ-SCOPED-PERMISSIONS.md`  
  - Acceptance: `docs/05-governance/07-acceptance/QLTY-BE-AUTHZ-SCOPE-01.acceptance.md`  
  - ADR: `docs/05-governance/05-adr/ADR-AUTHZ-SCOPE-01.md`

- QLTY-REST-AUTH-01 – Auth REST/DTO v1 geçişi  
  - Story: `docs/05-governance/02-stories/QLTY-REST-AUTH-01.md`  
  - Acceptance: `docs/05-governance/07-acceptance/QLTY-REST-AUTH-01.acceptance.md`

- QLTY-REST-USER-01 – User REST/DTO v1 geçişi  
  - Story: `docs/05-governance/02-stories/QLTY-REST-USER-01.md`  
  - Acceptance: `docs/05-governance/07-acceptance/QLTY-REST-USER-01.acceptance.md`

- E03-S02 – Theme Runtime Integration  
  - Story: `docs/05-governance/02-stories/E03-S02-Theme-Runtime-Integration.md`  
  - Acceptance: `docs/05-governance/07-acceptance/E03-S02-Theme-Runtime-Integration.acceptance.md`

- QLTY-MF-ROUTER-01 – MF Router Shared & Version Pin  
  - Story: `docs/05-governance/02-stories/QLTY-MF-ROUTER-01-Frontend-Router-Shared.md`  
  - Acceptance: `docs/05-governance/07-acceptance/QLTY-MF-ROUTER-01.acceptance.md`

- QLTY-FE-SPA-LOGIN-01 – SPA Login & silent-check-sso  
  - Story: `docs/05-governance/02-stories/QLTY-FE-SPA-LOGIN-01.md`  
  - Acceptance: `docs/05-governance/07-acceptance/QLTY-FE-SPA-LOGIN-01.acceptance.md`

- QLTY-FE-KEYCLOAK-01 – Frontend Keycloak/OIDC entegrasyonu  
  - Story: `docs/05-governance/02-stories/QLTY-FE-KEYCLOAK-01-Frontend-Keycloak-OIDC.md`  
  - Acceptance: `docs/05-governance/07-acceptance/QLTY-FE-KEYCLOAK-01.acceptance.md`

- QLTY-MF-UIKIT-01 – UI Kit Model Unification  
  - Story: `docs/05-governance/02-stories/QLTY-MF-UIKIT-01-Frontend-UI-Kit-Model.md`  
  - Acceptance: `docs/05-governance/07-acceptance/QLTY-MF-UIKIT-01.acceptance.md`  
  - Durum: Tamamlandı (paket modeli tek kaynak, mf_ui_kit remote sadece demo)

- QLTY-FE-SHARED-HTTP-01 – Ortak HTTP istemcisi  
  - Story: `docs/05-governance/02-stories/QLTY-FE-SHARED-HTTP-01.md`  
  - Acceptance: `docs/05-governance/07-acceptance/QLTY-FE-SHARED-HTTP-01.acceptance.md`
  - Not: Tüm MFE’ler `@mfe/shared-http/api` instance’ı üzerinden api-gateway’e `/api/v1/**` çağrıları yapar; Authorization/X-Trace-Id interceptor’ları tek merkezden yönetilir.

- QLTY-API-V1-STANDARDIZATION-01 – /api/v1 standardizasyonu  
  - Story: `docs/05-governance/02-stories/QLTY-API-V1-STANDARDIZATION-01.md`  
  - Acceptance: `docs/05-governance/07-acceptance/QLTY-API-V1-STANDARDIZATION-01.acceptance.md`
  - Not: Kullanıcı/rol/izin/varyant servisleri `/api/v1/**` path’i ve `items/total/page/pageSize` PagedResult zarfını zorunlu hale getirdi; legacy path’ler sadece @Deprecated olarak tutuluyor.

- QLTY-BE-KEYCLOAK-JWT-01 – Backend Keycloak JWT güvenliği  
  - Story: `docs/05-governance/02-stories/QLTY-BE-KEYCLOAK-JWT-01.md`  
  - Acceptance: `docs/05-governance/07-acceptance/QLTY-BE-KEYCLOAK-JWT-01.acceptance.md`

- QLTY-FE-SPA-LOGIN-01 – SPA Login & silent-check-sso  
  - Story: `docs/05-governance/02-stories/QLTY-FE-SPA-LOGIN-01.md`  
  - Acceptance: `docs/05-governance/07-acceptance/QLTY-FE-SPA-LOGIN-01.acceptance.md`
  - Not: Shell içindeki `/login` rotası kurumsal LoginPage ile yönetiliyor, ProtectedRoute auth yoksa `/login?redirect=` yönlendirmesi yapıyor ve silent-check-sso ile session yenileme dev/prod senaryolarında belgelenmiş durumda.

- SEC-VAULT-FAILOVER-01 – Vault Fail-Fast Fallback Strategy  
  - Story: `docs/05-governance/02-stories/SEC-VAULT-FAILOVER-01-Vault-Failover-Strategy.md`  
  - Acceptance: `docs/05-governance/07-acceptance/SEC-VAULT-FAILOVER-01.acceptance.md`  
  - Not: 2025-11-29 drill’i sırasında auth-service prod profilde Vault sırlarıyla ayağa kaldırıldı, Vault kapatıldığında servis fail-fast ile CrashLoop’a düştü ve Gateway `X-Serban-Outage-Code: VAULT_UNAVAILABLE` header’lı 503 döndürdü; Vault yeniden açıldığında health ve Gateway çağrıları normale döndü, tüm adımlar runbook + session-log’a işlendi.

- SEC-VAULT-FAILOVER-01 – Vault Fail-Fast Fallback Strategy  
  - Story: `docs/05-governance/02-stories/SEC-VAULT-FAILOVER-01-Vault-Failover-Strategy.md`  
  - Acceptance: `docs/05-governance/07-acceptance/SEC-VAULT-FAILOVER-01.acceptance.md`

- QLTY-FE-VERSIONS-01 – FE Version Pinning & MF Audit  
  - Story: `docs/05-governance/02-stories/QLTY-FE-VERSIONS-01-Frontend-Version-Pinning.md`  
  - Acceptance: `docs/05-governance/07-acceptance/QLTY-FE-VERSIONS-01.acceptance.md`

- QLTY-BE-KEYCLOAK-JWT-01 – Backend Keycloak JWT güvenliği  
  - Story: `docs/05-governance/02-stories/QLTY-BE-KEYCLOAK-JWT-01.md`  
  - Acceptance: `docs/05-governance/07-acceptance/QLTY-BE-KEYCLOAK-JWT-01.acceptance.md`

- QLTY-API-V1-STANDARDIZATION-01 – /api/v1 standardizasyonu  
  - Story: `docs/05-governance/02-stories/QLTY-API-V1-STANDARDIZATION-01.md`  
  - Acceptance: `docs/05-governance/07-acceptance/QLTY-API-V1-STANDARDIZATION-01.acceptance.md`

---

# ⏱ CYCLE TIME ÖZETİ

- Ortalama cycle time Story seviyesinde henüz ölçülmedi; gerçek süreler PROJECT_FLOW notları üzerinden izlenir.  
- Ölçüm kaynağı: FE/BE/SEC/OPS/ALPHA ticket’ları (PROJECT_FLOW notları).  
- Detaylı analiz gerektiğinde Story ve acceptance dokümanlarındaki tarih notlarıyla birleştirilir.

---

# 📌 PROJE GENEL DURUMU

**Genel Durum:** 🟧 Orta hızda ilerliyor (birden fazla Story aynı anda In Progress; E02-S02 Grid Mimarisi tamamlandı)  

**Riskler:**  
- Tek kişi çalışırken paralel In Progress Story sayısı yüksek; context switch maliyeti artabilir.  
- Manifest, telemetry, i18n/a11y ve permission registry için harici sistem erişimlerine (TMS, OTEL, Grafana Prod vb.) bağımlılık var.  

**Öneriler:**  
- Önceliği E02/E03 (Grid + Theme) ve E01-S05 (Login) Story’lerini DONE’a çekmeye ver.  
- Her PR’da ilgili Story acceptance dosyasını referans al ve maddeleri işaretle.  
- `PROJECT_FLOW.md`i düzenli aralıklarla kısa notlarla güncel tut; yeni Epic/Story ihtiyaçları için `FEATURE_REQUESTS.md` inbox listesini kullan.

---

# 🧭 KULLANIM TALİMATI

- Story eklerken:
  - `docs/05-governance/02-stories/` altında yeni Story dokümanı oluştur.  
  - Gerekliyse `06-specs/` ve `07-acceptance/` altında ilgili spec/acceptance dosyalarını ekle.  
  - Bu dosyadaki tabloya yeni Story satırını ve linklerini ekle.  
- Story durumu değiştiğinde (`Durum:` alanı):
  - Tablodaki “Son Durum” simgesini/girdisini güncelle (⏳ Planlandı / 🔧 Devam ediyor / 🔎 Review / ✔ Tamamlandı).  
- Board/akış güncellemeleri:
- Ticket hareketleri PROJECT_FLOW üzerinde güncellenir; PROJECT_FLOW Story ve alt işlerin resmi görünümüdür.  
- Doc hygiene sırasında:
  - `PROJECT_FLOW.md` ve ilgili Story/Acceptance/Spec dokümanlarının birbiriyle uyumlu olduğundan emin ol.
  - Bir ADR uygulanıp ilgili Story `Done` olduğunda; kararın sonucu resmi mimari dokümanlara işlenmelidir (`docs/01-architecture/**/*`, gerektiğinde runbook/operasyon dokümanları). ADR karar kaydıdır; sistemin bugün nasıl çalıştığı her zaman mimari dokümanlarda anlatılır.

---

# 📌 NOT

Bu dosya, tek kişiyle de olsa **mini Jira + Kanban + Flow yönetimi** işlevi görür:  
Story seviyesinde neyin planlandığı, neyin devam ettiği, nerede blokaj olduğu ve hangi dokümanlarla ilişkili olduğu tek bakışta görülebilir.  

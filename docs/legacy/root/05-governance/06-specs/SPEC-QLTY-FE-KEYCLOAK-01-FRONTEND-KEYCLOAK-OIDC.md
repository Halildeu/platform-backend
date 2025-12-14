# SPEC-QLTY-FE-KEYCLOAK-01-FRONTEND-KEYCLOAK-OIDC
**Başlık:** Frontend Keycloak / OIDC Entegrasyonu v1.0  
**Versiyon:** v1.0  
**Tarih:** 2025-11-29  

**İlgili Dokümanlar:**  
- EPIC: `docs/05-governance/01-epics/E01_Identity.md`  
- ADR: `docs/05-governance/05-adr/ADR-003-auth-and-broadcast-channel.md`, `docs/05-governance/05-adr/ADR-010-security-pipeline.md`  
- ACCEPTANCE: `docs/05-governance/07-acceptance/QLTY-FE-KEYCLOAK-01.acceptance.md`  
- STORY: `docs/05-governance/02-stories/QLTY-FE-KEYCLOAK-01-Frontend-Keycloak-OIDC.md`  
- STYLE GUIDE: `docs/00-handbook/NAMING.md`, `docs/00-handbook/STYLE-FE-001.md`, `docs/00-handbook/STYLE-API-001.md`  

**Etkilenen Modüller / Servisler:**  

| Modül/Servis  | Açıklama / Sorumluluk                              | İlgili ADR |
|---------------|----------------------------------------------------|------------|
| frontend-shell| keycloak-js init, auth store ve ProtectedRoute      | ADR-003    |
| shared-http   | Axios interceptor, Bearer + TraceId header’ları     | ADR-010    |
| api-gateway   | `/api/v1/**` uçlarının JWT beklentisi / kontrolü    | ADR-003    |
| ops-ci        | security-guardrails + i18n/a11y pipeline güncellemeleri | ADR-010 |

---

# 1. Amaç (Purpose)
Frontend shell ve MFE’lerin prod/test ortamlarında Keycloak OIDC client’ı üzerinden login/register/refresh/logout akışlarını güvenli şekilde yürütmesini tanımlamak; axios tabanlı HTTP katmanının Bearer token ve TraceId taşımasını zorunlu kılmak ve dev/local profillerinde permitAll davranışını kontrollü biçimde sürdürmek.

---

# 2. Kapsam (Scope)

### Kapsam içi
- `keycloak-js` client ayarlarının (url/realm/clientId PKCE) konfigürasyonu.  
- Shell auth merkezi, BroadcastChannel + storage fallback ile oturum paylaşımı.  
- `@mfe/shared-http` axios instance’ında Authorization + `X-Trace-Id` interceptor’ları.  
- Dev/local profillerinde permitAll modunun sınırları (mock token, feature flag).

### Kapsam dışı
- Backend Keycloak realm yönetimi ve client oluşturma (QLTY-BE-KEYCLOAK-JWT-01 kapsamı).  
- MFA veya sosyal login gibi ileri doğrulama özellikleri.  

---

# 3. Tanımlar (Definitions)
- **Keycloak-js:** Keycloak’un resmi JS adaptörü; PKCE + refresh akışlarını sürer.  
- **BroadcastChannel:** Aynı tarayıcıdaki sekmeler arasında auth state senkronu sağlar; storage event fallback ile desteklenir.  
- **Shared HTTP:** `packages/shared-http` altında axios instance ve interceptor’ları.  

---

# 4. Kullanıcı Senaryoları (User Flows)

1. **Başarılı login:** Shell `/login` ekranında Keycloak formu açılır, kullanıcı giriş yapar; callback token’ı belleğe yazılır, shared-http interceptörü Bearer gönderir, Shell ProtectedRoute redirect eder.  
2. **Session refresh:** keycloak-js silent refresh tetikler; access token yenilenir, BroadcastChannel yeni payload’ı paylaşır.  
3. **Logout / session timeout:** Kullanıcı logout veya token süresi dolduğunda shell store temizlenir, tüm sekmeler `/login?redirect=...` ekranına döner.  
4. **Dev profil:** Keycloak kapalıyken `VITE_ENABLE_FAKE_AUTH=true` ile mock token üretilebilir; bu mod yalnız local/dev profilinde allowlist’lenir.  

---

# 5. Fonksiyonel Gereksinimler (Functional Requirements)
- **FR-FE-OIDC-01:** Shell’de tek auth merkezi bulunur; tüm MFE’ler login/logout çağrılarını shell-services API’si üzerinden yapar.  
- **FR-FE-OIDC-02:** keycloak-js init parametreleri `.env` değerlerinden yüklenir (`VITE_KEYCLOAK_URL`, `VITE_KEYCLOAK_REALM`, `VITE_KEYCLOAK_CLIENT_ID`).  
- **FR-FE-OIDC-03:** `@mfe/shared-http` axios instance’ı Bearer + `X-Trace-Id` header’larını otomatik ekler; response interceptor 401’lerde shell login akışını tetikler.  
- **FR-FE-OIDC-04:** BroadcastChannel (`shell-auth`) access token, profile snapshot ve logout event’lerini taşır; desteklenmeyen tarayıcılarda localStorage `shell-auth-sync` anahtarı fallback olarak kullanılır.  
- **FR-FE-OIDC-05:** Logout veya session-expired sinyali geldiğinde shared-http queue’daki talepler iptal edilir ve shell store temizlenir.  
- **FR-FE-OIDC-06:** Dev/local profilinde permitAll zorunluluğu belgelidir; `VITE_AUTH_MODE=permitAll` iken shared-http interceptor Bearer göndermeden gateway’e istek atar.  

---

# 6. İş Kuralları (Business Rules)
- **BR-FE-OIDC-01:** Prod/test profillerinde `VITE_AUTH_MODE=keycloak` harici değer kabul edilmez; build pipeline bu değeri doğrular.  
- **BR-FE-OIDC-02:** Keycloak’dan dönen kullanıcı bilgileri yalnız bellek içinde tutulur; localStorage veya sessionStorage’a token yazılmaz.  
- **BR-FE-OIDC-03:** Shell harici hiçbir MFE, keycloak-js init etmez; auth store import ederek operasyon yapar.  

---

# 7. Veri Modeli (Data Model)
- **Auth store shape:** `{ token: string | null, refreshToken: string | null, profile: KeycloakProfile | null, expiresAt: number }`.  
- **Broadcast payload:** `{ event: 'LOGIN' | 'LOGOUT' | 'REFRESH', token?: string, profile?: ProfileDTO, traceId?: string }`.  
- **Storage fallback key:** `serban.shell.authState` (JSON serialize, token hariç).  

---

# 8. API Tanımı (API Spec)
- `shared-http` baseURL: `VITE_GATEWAY_URL || http://localhost:8080/api`.  
- Axios request interceptor: `Authorization: Bearer <token>`, `X-Trace-Id: ${uuid}`.  
- Response interceptor: 401 → shell `authService.logout({ reason: 'session_expired', redirect: currentPath })`; diğer 4xx/5xx’ler toast/report katmanına aktarılır.  
- Shell ProtectedRoute: `GET /api/auth/session` endpoint’i ile session doğrular; 200 ise children render, aksi login redirect.  

---

# 9. Validasyon Kuralları (Validation Rules)
- `.env` dosyalarında `VITE_KEYCLOAK_URL`, `VITE_KEYCLOAK_REALM`, `VITE_KEYCLOAK_CLIENT_ID`, `VITE_KEYCLOAK_SILENT_CHECK_URI` zorunludur.  
- Prod/test build’lerinde `VITE_AUTH_MODE` değeri `keycloak` değilse CI kırılır.  
- PermitAll modu yalnız `NODE_ENV=development` + `VITE_AUTH_MODE=permitAll` kombinasyonunda enable olur.  

---

# 10. Hata Kodları (Error Codes)
- 401 `auth_required`: gateway veya servis JWT bekliyor; UI login’e yönlendirir.  
- 401 `session_expired`: keycloak refresh başarısız; kullanıcı login’e alınır.  
- 429/5xx durumları shared-http error handler tarafından `MaintenanceBanner` tetikleyicisine taşınır.  

---

# 11. Non-Fonksiyonel Gereksinimler (NFR)
- TTFA: Login page ilk render < 1.5s (sıcak).  
- Token refresh aralığı: access token süresi 5 dk, refresh silent check interval 60s.  
- A11y: Login formu label’lı inputlar + axe smoke testi.  
- Güvenlik: Token bellek dışında saklanmaz; console log’ları maskelenir.  

---

# 12. İzlenebilirlik (Traceability)
Bu SPEC şu dokümanlarla bağlıdır:
- Story / Acceptance: QLTY-FE-KEYCLOAK-01  
- ADR-003 auth broadcast + ADR-010 security pipeline kararları  
- Frontend / Backend ARCH-STATUS “Security” ve “Auth” bölümleri  
- Runbook: `docs/04-operations/01-runbooks/keycloak-admin-guide.md`  

Bu doküman, Frontend Keycloak / OIDC entegrasyonu için teknik tasarımın tek kaynağıdır.

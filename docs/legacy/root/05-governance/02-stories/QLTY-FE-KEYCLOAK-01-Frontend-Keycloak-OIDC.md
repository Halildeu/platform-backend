# Story QLTY-FE-KEYCLOAK-01 – Frontend Keycloak / OIDC Entegrasyonu (prod/test)

- Epic: QLTY – Güvenlik Sertleştirme  
- Story Priority: 002  
- Tarih: 2025-11-22  
- Durum: Done  
- Modüller / Servisler: frontend-shell, shared-http, api-gateway, ops-ci

## Kısa Tanım

Prod/test ortamlarında Keycloak tabanlı OIDC login akışını devreye almak; axios interceptor ile Bearer token iletimi, başarısız token’da 401/redirect akışı, dev/local’de permitAll.

## İş Değeri

- Güvenli ve standart kimlik doğrulama sağlar.  
- Prod/test’te tutarlı JWT zorunluluğu ile güvenlik borcunu kapatır.  
- FE/BE auth sözleşmesini netleştirir.

## Bağlantılar (Traceability Links)

- SPEC: `docs/05-governance/06-specs/SPEC-QLTY-FE-KEYCLOAK-01-FRONTEND-KEYCLOAK-OIDC.md`  
- ACCEPTANCE: `docs/05-governance/07-acceptance/QLTY-FE-KEYCLOAK-01.acceptance.md`  
- ARCH: `frontend/docs/01-architecture/01-shell/01-frontend-architecture.md`, `backend/docs/01-architecture/01-system/01-backend-architecture.md`  
- STYLE: `docs/00-handbook/STYLE-FE-001.md`, `docs/00-handbook/STYLE-API-001.md`  
- AGENT: `docs/00-handbook/AGENT-CODEX.md`

## Kapsam

### In Scope
- keycloak-js config (url/realm/clientId) ve prod/test profilleri için zorunlu akış.  
- Axios interceptor → Authorization: Bearer <token>.  
- Dev/local’de permitAll korunması.  
- ARCH/PROJECT_FLOW/session-log güncellemeleri.

### Out of Scope
- Backend Keycloak konfigürasyon değişiklikleri (mevcut prod ayarları korunur).  
- MFA/advanced auth (gelecek faz).

## Task Flow (Ready → InProgress → Review → Done)

```text
+--------------+-------------------------------------------------------------+------------+-------------+------------+-------------+
| Modül/Servis | Task                                                        | Ready      | InProgress  | Review     | Done        |
+--------------+-------------------------------------------------------------+------------+-------------+------------+-------------+
| frontend-shell| keycloak-js client config (prod/test)                      | 2025-11-22 | 2025-11-30  | 2025-11-30 | 2025-11-30  |
| shared-http   | Axios interceptor + Bearer header                           | 2025-11-22 | 2025-11-30  | 2025-11-30 | 2025-11-30  |
| frontend-shell| Protected route davranışı (401/redirect)                    | 2025-11-22 | 2025-11-30  | 2025-11-30 | 2025-11-30  |
| ops-ci        | Doküman + PROJECT_FLOW + session-log güncelle               | 2025-11-22 | 2025-11-30  | 2025-11-30 | 2025-11-30  |
+--------------+-------------------------------------------------------------+------------+-------------+------------+-------------+
```

## Fonksiyonel Gereksinimler

1. Prod/test’te login yalnız Keycloak OIDC ile gerçekleşir.  
2. Axios interceptor tüm API çağrılarına Bearer ekler; token yoksa login’e yönlendirir.  
3. Dev/local profillerinde permitAll; token zorunlu değil.

## Non-Functional Requirements

- Güvenlik: Token leak yok, log’larda maskeleme.  
- Observability: 401/redirect olayları loglanır.  
- Performans: Auth akışı kabul edilebilir gecikme (<1s token refresh).

## İş Kuralları / Senaryolar

- “Token süresi doldu” → refresh/redirect; API 401 dönerse UI login’e yönlendirir.  
- “Dev profile” → permitAll, Bearer gönderilmez.

## Interfaces (API / DB / Event)

### API
- Gateway üzerinden tüm `/api/v1/**` çağrıları Bearer ile.

### Database / Events
- Yok.

## Acceptance Criteria

Bu Story için acceptance:  
`docs/05-governance/07-acceptance/QLTY-FE-KEYCLOAK-01.acceptance.md`

## Definition of Done

- [x] Acceptance maddeleri sağlandı.  
- [x] Prod/test’te Keycloak OIDC ile login zorunlu.  
- [x] Dev/local permitAll davranışı korunuyor.  
- [x] Dokümanlar, PROJECT_FLOW, session-log güncellendi.  
- [x] Kod review + CI yeşil.

## Notlar

- Backend tarafında mevcut Keycloak/permitAll profilleri korunur; sadece FE entegrasyonu kapsamda.

## Dependencies
- MF router pin (QLTY-MF-ROUTER-01) – guarded route davranışı için.  
- Vault failover (SEC-VAULT-FAILOVER-01) – backend erişim güvenilirliği.

## Risks
- Prod/test token misconfig 401 fırtınasına yol açabilir; rollout için feature flag önerilir.

## Flow / Iteration İlişkileri
| Flow ID   | Durum    | Not |
|-----------|---------|-----|
| Flow-Security-Frontend | Planned | Keycloak/OIDC FE entegrasyonu |

## İlgili Artefaktlar
- `frontend/docs/01-architecture/01-shell/01-frontend-architecture.md`  
- `docs/00-handbook/STYLE-FE-001.md`

# Epic E01 – Identity

- Epic Priority: 100  
- Durum: In Progress

## Açıklama

Kurumsal ERP Shell için kimlik ve oturum yönetimi katmanını temsil eder. Login/register/unauthorized akışlarını, auth servisleri ile entegrasyonu ve frontend auth UI/UX standartlarını kapsar; Ant Design’dan Tailwind + UI Kit primitives’ine geçiş ve çok dilli giriş deneyimi de bu Epic altında ele alınır.

EPIC_BUSINESS_CONTEXT:
- Tüm modüllere (Access, Reporting, Audit, Users vb.) tek bir Shell üzerinden güvenli giriş.
- Farklı ülkeler/diller için (TR/EN/DE/ES) tutarlı login/register deneyimi.
- Güvenlik gereksinimleri ile UX beklentilerinin dengelenmesi (hata mesajları, parola politikaları, oturum süresi).

## Fonksiyonel Kapsam

- Shell login/register/unauthorized ekranları ve login popover.
- Auth servis API’leri ile entegrasyon (login, register, session check).
- Dil ve tema seçimlerinin auth deneyimi ile entegrasyonu.
- Kimlik akışında kullanılan UI Kit bileşenleri (Button, Input, Form layout, Toast/Notification).

## Non-Functional Requirements (Epic Seviyesi)

- Performance:
  - Auth ekranlarının ilk yüklemesi hafif ve hızlı olmalı; tema/i18n preload aşırı gecikme yaratmamalı.
- Security:
  - Hata mesajları bilgi sızdırmamalı; parola ve oturum yönetimi güvenlik rehberleri ile uyumlu olmalı.
- UX:
  - Tüm auth akışları klavye ile kullanılabilir olmalı; a11y ve kontrast gereksinimlerini karşılamalı.

## Story Listesi

| Story ID | Story Adı                                        | Durum        | Story Dokümanı                                          |
|----------|--------------------------------------------------|-------------|---------------------------------------------------------|
| E01-S05  | Shell Login & Register (Tailwind + i18n)         | In Progress | 02-stories/E01-S05-Login.md                             |
| E01-S10  | Auth & BroadcastChannel Full MFE Sync            | Planned     | 02-stories/E01-S10-Auth-BroadcastChannel-Full-MFE-Sync.md |
| E01-S90  | Identity Support & Small Tasks                   | Done        | 02-stories/E01-S90-Identity-Support.md                  |

## Doküman Zinciri (Traceability)

- Epic: `docs/05-governance/01-epics/E01_Identity.md`
- Story:
  - `docs/05-governance/02-stories/E01-S05-Login.md`
- SPEC:
  - `docs/05-governance/06-specs/SPEC-E01-S05-IDENTITY-AUTH-V1.md`
- Acceptance:
  - `docs/05-governance/07-acceptance/E01-S05-Login.acceptance.md`
- ADR:
  - `docs/05-governance/05-adr/ADR-003-auth-and-broadcast-channel.md`
  - `docs/05-governance/05-adr/ADR-008-i18n-and-dictionary-packaging.md`
  - `docs/05-governance/05-adr/ADR-016-theme-layout-system.md`

## Story–Sprint Eşleştirmeleri

| Story ID | Sprint ID | Not                                            |
|----------|-----------|------------------------------------------------|
| E01-S05  | Sprint 01 | Auth UI + i18n refactor ilk dalga             |
| E01-S10  | (TBD)     | BroadcastChannel + tüm MFE zinciri senkron login/logout |

## Bağımlılıklar

- Auth-service API sözleşmesi (`docs/03-delivery/api/auth.api.md`).
- Security/compliance rehberleri (`docs/agents/*`).
- Theme/Layout sistemi (ADR-016).

## Riskler

- Auth akışındaki görsel/mimari değişiklikler yanlış kurgulanırsa kullanıcıların login olamaması veya karışık deneyim yaşaması.
- i18n sözlük drift’i; auth metinlerinin diğer modüllerle uyumsuz hale gelmesi.

## İlgili Artefaktlar

- `docs/05-governance/05-adr/ADR-003-auth-and-broadcast-channel.md`
- `docs/05-governance/05-adr/ADR-008-i18n-and-dictionary-packaging.md`
- `docs/05-governance/05-adr/ADR-016-theme-layout-system.md`

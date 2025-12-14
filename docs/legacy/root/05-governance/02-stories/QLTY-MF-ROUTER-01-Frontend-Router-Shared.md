# Story QLTY-MF-ROUTER-01 – MF Router Shared & Version Pin Hardening

- Epic: QLTY – Frontend Hardening  
- Story Priority: 001  
- Tarih: 2025-11-22  
- Durum: Planlandı

## Kısa Tanım

Frontend MFE’lerinde `react-router` ve `react-router-dom` bağımlılıklarının tek kopya/singleton olarak paylaşılması, versiyon drift’inin engellenmesi ve MF smoke testleriyle doğrulanması.

## İş Değeri

- Router kopya çatışmalarından kaynaklı runtime hatalarını önler.  
- Navigation, guard ve history davranışlarını tutarlı kılar.  
- MF host/remote uyumsuzluklarını CI’de erken yakalar.

## Bağlantılar (Traceability Links)

- ACCEPTANCE: `docs/05-governance/07-acceptance/QLTY-MF-ROUTER-01.acceptance.md`  
- STYLE: `docs/00-handbook/STYLE-FE-001.md`, `docs/00-handbook/STYLE-API-001.md`  
- ARCH: `frontend/docs/01-architecture/01-shell/01-frontend-architecture.md`, `backend/docs/01-architecture/01-system/01-backend-architecture.md`  
- AGENT: `docs/00-handbook/AGENT-CODEX.md`

## Kapsam

### In Scope
- Shell ve tüm remote MFE’lerde MF shared listesine router paketlerinin singleton eklenmesi ve versiyon pin’i.  
- Playwright/MF smoke testinde router drift kontrolü.  
- ARCH/PROJECT_FLOW güncellemeleri.

### Out of Scope
- Keycloak entegrasyonu (ayrı Story).  
- UI Kit model seçimi (ayrı Story).

## Task Flow (Ready → InProgress → Review → Done)

```text
+-------------------------------------------------------------+------------+-------------+---------+---------+
| Task                                                        | Ready      | InProgress  | Review  | Done    |
+-------------------------------------------------------------+------------+-------------+---------+---------+
| MF shared listesinde react-router/react-router-dom pinle    | 2025-11-22 |             |         |         |
| Playwright/MF smoke testine router drift kontrolü ekle      | 2025-11-22 |             |         |         |
| ARCH + PROJECT_FLOW güncelle                                | 2025-11-22 |             |         |         |
+-------------------------------------------------------------+------------+-------------+---------+---------+
```

## Fonksiyonel Gereksinimler

1. Tüm MFE’lerde router bağımlılıkları singleton ve aynı versiyon olmalı.  
2. MF smoke testi router drift tespiti yapmalı.  
3. ARCH dokümanında v1 router paylaşım kuralı belirtilmeli.

## Non-Functional Requirements

- Build: MF paylaşımı bozulursa build/smoke test fail.  
- CI: Paylaşılan versiyon drift’i CI’da kırmızıya düşürmeli.

## İş Kuralları / Senaryolar

- “Singleton router” → host ve remote aynı router kopyasını kullanır, navigation state tutarlı kalır.

## Interfaces (API / DB / Event)

### API
- Router paylaşımları MF config seviyesinde; HTTP API yok.

### Database / Events
- Yok.

## Acceptance Criteria

Bu Story için güncel acceptance kriterleri:  
`docs/05-governance/07-acceptance/QLTY-MF-ROUTER-01.acceptance.md`

## Definition of Done

- [ ] Acceptance maddeleri sağlandı.  
- [ ] MF shared listesi router için singleton + versiyon pinli.  
- [ ] Playwright/MF smoke test router drift testini içeriyor.  
- [ ] ARCH ve PROJECT_FLOW güncellendi.  
- [ ] Kod review ve CI yeşil.

## Notlar

- react-query devtools/cli pin’leri QLTY-FE-VERSIONS-01 altında ele alınacak.

## Dependencies
- QLTY-FE-VERSIONS-01 – versiyon pin audit.  
- QLTY-FE-KEYCLOAK-01 – auth router guard senaryoları.

## Risks
- Router pin’inin olmaması prod’da çift kopya riskini artırır.

## Flow / Iteration İlişkileri
| Flow ID   | Durum    | Not |
|-----------|---------|-----|
| Flow-Frontend-Hardening | Planned | Router/shared sertleştirme |

## İlgili Artefaktlar
- `frontend/docs/01-architecture/01-shell/01-frontend-architecture.md`  
- `docs/00-handbook/STYLE-FE-001.md`

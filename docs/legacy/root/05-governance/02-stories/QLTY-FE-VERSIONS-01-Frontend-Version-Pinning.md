# Story QLTY-FE-VERSIONS-01 – Frontend Version Pinning & MF Shared Audit

- Epic: QLTY – Frontend Hardening  
- Story Priority: 005  
- Tarih: 2025-11-22  
- Durum: Planlandı

## Kısa Tanım

React Router, React Query ve devtools/cli gibi kritik FE bağımlılıklarının paket ve MF shared listelerinde versiyon drift’inin önlenmesi; audit + otomatik kontrol.

## İş Değeri

- Host/remote runtime uyuşmazlıklarını engeller.  
- Build ve runtime stabilitesini artırır.  
- MF paylaşımı için tekil versiyon kaynağı oluşturur.

## Bağlantılar (Traceability Links)

- ACCEPTANCE: `docs/05-governance/07-acceptance/QLTY-FE-VERSIONS-01.acceptance.md`  
- STYLE: `docs/00-handbook/STYLE-FE-001.md`  
- ARCH: `frontend/docs/01-architecture/01-shell/01-frontend-architecture.md`  
- AGENT: `docs/00-handbook/AGENT-CODEX.md`

## Kapsam

### In Scope
- package.json ve MF shared config audit (router/query/devtools).  
- Versiyon pin’lerinin hizalanması ve doğrulama betiği/smoke testi.  
- ARCH/PROJECT_FLOW güncellemeleri.

### Out of Scope
- Yeni kütüphane eklemek.  
- UI Kit modeli (ayrı Story).

## Task Flow (Ready → InProgress → Review → Done)

```text
+-------------------------------------------------------------+------------+-------------+---------+---------+
| Task                                                        | Ready      | InProgress  | Review  | Done    |
+-------------------------------------------------------------+------------+-------------+---------+---------+
| package.json + MF shared audit (router/query/devtools)      | 2025-11-22 |             |         |         |
| Versiyon pin ve doğrulama betiği/smoke test                 | 2025-11-22 |             |         |         |
| ARCH + PROJECT_FLOW güncelle                                | 2025-11-22 |             |         |         |
+-------------------------------------------------------------+------------+-------------+---------+---------+
```

## Fonksiyonel Gereksinimler

1. Router/query/devtools versiyonları tüm MFE’lerde hizalı olmalı.  
2. MF shared listesi ile package.json versiyonları uyuşmalı.  
3. Drift tespit eden küçük bir script veya smoke test olmalı.

## Non-Functional Requirements

- CI: Drift algılanırsa build fail.  
- Dokümantasyon: Versiyon pin kuralları ARCH’ta yer almalı.

## İş Kuralları / Senaryolar

- “Yeni paket güncellemesi” → audit/test otomatik uyarı verir, MF shared listesi güncellenir.

## Interfaces (API / DB / Event)

### API
- Yok; audit build/test aşamasında.

## Acceptance Criteria

Bu Story için acceptance:  
`docs/05-governance/07-acceptance/QLTY-FE-VERSIONS-01.acceptance.md`

## Definition of Done

- [ ] Acceptance maddeleri sağlandı.  
- [ ] Versiyon drift audit + pin tamamlandı.  
- [ ] ARCH, PROJECT_FLOW, session-log güncellendi.  
- [ ] Kod review + CI yeşil.

## Notlar

- QLTY-MF-ROUTER-01 ile birlikte yürütülmesi önerilir.

## Dependencies
- QLTY-MF-ROUTER-01 – router pin.  
- QLTY-MF-UIKIT-01 – UI Kit modeline göre shared/pin düzeni.

## Risks
- MF shared listesi güncellenmezse prod’da gizli drift yaşanabilir.

## Flow / Iteration İlişkileri
| Flow ID   | Durum    | Not |
|-----------|---------|-----|
| Flow-Version-Pinning | Planned | FE kritik bağımlılıkların pin’i |

## İlgili Artefaktlar
- `frontend/docs/01-architecture/01-shell/01-frontend-architecture.md`  
- `docs/00-handbook/STYLE-FE-001.md`

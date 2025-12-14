# Story QLTY-MF-UIKIT-01 – UI Kit Model Unification (Package vs Remote)

- Epic: QLTY – Frontend Hardening  
- Story Priority: 003  
- Tarih: 2025-11-22  
- Durum: Planlandı

## Kısa Tanım

UI Kit tüketim modelini (package vs MF remote) tek bir resmi modele indirip tüm import örneklerini, MF config’ini ve dokümanları hizalamak.

## İş Değeri

- UI bileşenlerinin kullanımında belirsizliği ortadan kaldırır.  
- MF remote/paket çakışmalarından doğan runtime hatalarını ve bundle şişmesini azaltır.  
- Tasarım sisteminin SSOT olmasını sağlar.

## Bağlantılar (Traceability Links)

- ACCEPTANCE: `docs/05-governance/07-acceptance/QLTY-MF-UIKIT-01.acceptance.md`  
- STYLE: `docs/00-handbook/STYLE-FE-001.md`, `docs/00-handbook/STYLE-API-001.md`  
- ARCH: `frontend/docs/01-architecture/01-shell/01-frontend-architecture.md`  
- AGENT: `docs/00-handbook/AGENT-CODEX.md`

## Kapsam

### In Scope
- Tek resmi UI Kit modeli seçimi (paket veya remote, gerekirse hibrit).  
- Import örneklerinin ve MF config’inin seçilen modele göre güncellenmesi.  
- ARCH/PROJECT_FLOW güncellemeleri.

### Out of Scope
- Yeni UI bileşeni geliştirme.  
- Tema/token değişiklikleri (mevcut sistem korunur).

## Task Flow (Ready → InProgress → Review → Done)

```text
+-------------------------------------------------------------+------------+-------------+---------+---------+
| Task                                                        | Ready      | InProgress  | Review  | Done    |
+-------------------------------------------------------------+------------+-------------+---------+---------+
| UI Kit modeli seçimi (paket/remote)                         | 2025-11-22 |             |         |         |
| Import örnekleri + MF config hizalama                       | 2025-11-22 |             |         |         |
| ARCH + PROJECT_FLOW + docs güncelle                         | 2025-11-22 |             |         |         |
+-------------------------------------------------------------+------------+-------------+---------+---------+
```

## Fonksiyonel Gereksinimler

1. Seçilen model tek SSOT olarak dokümante edilir.  
2. Tüm örnekler ve MF config seçilen modele uyar; karışık kullanım kalmaz.  
3. Build/lint/test seçilen modelle sorunsuz çalışır.

## Non-Functional Requirements

- Bundle boyutu: gereksiz çift import engellenir.  
- Dev deneyimi: import path’leri tek tip.

## İş Kuralları / Senaryolar

- “UI bileşeni kullanımı” → seçilen modelden import; diğer model kaldırılır/deprecate edilir.

## Interfaces (API / DB / Event)

### API
- Yok (UI Kit paylaşımı MF/paket düzeyinde).

## Acceptance Criteria

Bu Story için acceptance:  
`docs/05-governance/07-acceptance/QLTY-MF-UIKIT-01.acceptance.md`

## Definition of Done

- [ ] Acceptance maddeleri sağlandı.  
- [ ] UI Kit modeli tekil ve dokümante.  
- [ ] MF config + import örnekleri hizalı.  
- [ ] ARCH, PROJECT_FLOW, session-log güncellendi.  
- [ ] Kod review + CI yeşil.

## Notlar

- Seçim yapılırken MF remote erişilebilirliği vs. paket publish süreci dikkate alınmalı.

## Dependencies
- QLTY-MF-ROUTER-01 – MF shared uyumu.  
- QLTY-FE-VERSIONS-01 – versiyon drift audit.

## Risks
- Yanlış model seçimi build/publish süreçlerini etkileyebilir.

## Flow / Iteration İlişkileri
| Flow ID   | Durum    | Not |
|-----------|---------|-----|
| Flow-UI-Kit | Planned | Model tekilleştirme |

## İlgili Artefaktlar
- `frontend/docs/01-architecture/01-shell/01-frontend-architecture.md`  
- `docs/00-handbook/STYLE-FE-001.md`

# Epic E04 – Platform Manifest & Contracts

- Epic Priority: 400  
- Durum: In Progress

## Açıklama

Remote manifest, PageLayout manifest modeli ve şema/kontrat gating mekanizmalarını kapsar. Amaç, Shell ve tüm MFE zinciri için manifest tabanlı yükleme, SRI/CSP koruması ve CI'da kontrat doğrulaması ile güvenli bir platform katmanı sağlamaktır.

EPIC_BUSINESS_CONTEXT:
- Remote ve sözlük artefact’ları için tek kaynaktan yönetilen manifest ve sözleşme modeli.
- Sürüm uyumsuzluğu ve supply chain risklerini manifest + SRI ile fail‑closed yakalamak.
- CI/CD’de manifest ve ShellServices sözleşmelerini otomatik kontrat testleriyle korumak.

## Fonksiyonel Kapsam

- Remote manifest servisleri, manifest JSON şemaları ve semver aralığı yönetimi.
- PageLayout & PageManifest modeli (sayfa meta, permission, grid/layout yapı taşları).
- Manifest ve ShellServices için JSON Schema / TypeScript tip paketleri; contract test adımları.

## Non-Functional Requirements (Epic Seviyesi)

- Manifest doğrulama süresi p95 ≤ 200 ms.
- Şema/kontrat uyumsuzluklarının production yerine CI’da yakalanması.
- Sürüm/manifest değişikliklerinin semver ve audit log ile izlenebilir olması.

## Story Listesi

| Story ID | Story Adı                          | Durum    | Story Dokümanı                             |
|----------|------------------------------------|---------|--------------------------------------------|
| E04-S01  | Manifest & Sözleşme Platformu v1.0 | Planned | 02-stories/E04-S01-Manifest-Platform-v1.md |

## Doküman Zinciri (Traceability)

- Epic: `docs/05-governance/01-epics/E04_PlatformManifestAndContracts.md`
- Story:
  - `docs/05-governance/02-stories/E04-S01-Manifest-Platform-v1.md`
- SPEC:
  - `docs/05-governance/06-specs/SPEC-E04-S01-PLATFORM-MANIFEST-V1.md`
- Acceptance:
  - `docs/05-governance/07-acceptance/E04-S01-Manifest-Platform-v1.acceptance.md`
- ADR:
  - `docs/05-governance/05-adr/ADR-001-remote-manifest-and-sri.md`
  - `docs/05-governance/05-adr/ADR-002-page-layout-and-manifest-model.md`
  - `docs/05-governance/05-adr/ADR-007-contract-and-schema-gating.md`

## Story–Sprint Eşleştirmeleri

| Story ID | Sprint ID | Not                                              |
|----------|-----------|--------------------------------------------------|
| E04-S01  | (TBD)     | Manifest + PageLayout + contract test ilk dalga |

## Bağımlılıklar

- Gateway ve CDN katmanı (manifest dağıtımı).
- Schema paketleri için npm/verdaccio altyapısı.
- CI/CD pipeline’larında contract test adımları.

## Riskler

- Şema/manifest sürümlerinin drift etmesi; backward compatibility’nin ihmal edilmesi.
- Manifest doğrulaması nedeniyle açılış süresinin kontrolsüz artması.

## İlgili Artefaktlar

- `docs/05-governance/05-adr/ADR-001-remote-manifest-and-sri.md`
- `docs/05-governance/05-adr/ADR-002-page-layout-and-manifest-model.md`
- `docs/05-governance/05-adr/ADR-007-contract-and-schema-gating.md`

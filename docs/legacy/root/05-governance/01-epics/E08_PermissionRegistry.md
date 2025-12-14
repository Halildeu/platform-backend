# Epic E08 – Permission Registry

- Epic Priority: 800  
- Durum: In Progress

## Açıklama

Permission anahtarlarının tek bir registry üzerinden yönetilmesini, manifest ve PageLayout ile entegrasyonunu ve yaşam döngüsü (active/deprecated) takibini kapsar.

EPIC_BUSINESS_CONTEXT:
- Access modülü ve rota guard’ları için tek kaynaklı permission sözlüğü.
- Deprecate edilen permission’ların kontrollü temizlenmesi.
- Denetlenebilir yetkilendirme modeli ve audit görünürlüğü.

## Fonksiyonel Kapsam

- Permission registry formatı (JSON/YAML) ve Backstage entegrasyonu.
- Manifest/PageLayout tarafında permission key doğrulaması ve contract testleri.
- Sunset planı, deprecation metadata’sı ve UI tarafındaki uyarı metinleri.

## Non-Functional Requirements (Epic Seviyesi)

- Registry dışı permission key kullanımının CI’da engellenmesi.
- Registry değişikliklerinin versiyonlu ve audit edilebilir olması.

## Story Listesi

| Story ID | Story Adı                  | Durum    | Story Dokümanı                             |
|----------|----------------------------|---------|--------------------------------------------|
| E08-S01  | Permission Registry v1.0   | Planned | 02-stories/E08-S01-Permission-Registry.md  |

## Doküman Zinciri (Traceability)

- Epic: `docs/05-governance/01-epics/E08_PermissionRegistry.md`
- Story:
  - `docs/05-governance/02-stories/E08-S01-Permission-Registry.md`
- SPEC:
  - `docs/05-governance/06-specs/SPEC-E08-S01-PERMISSION-REGISTRY-V1.md`
- Acceptance:
  - `docs/05-governance/07-acceptance/E08-S01-Permission-Registry.acceptance.md`
- ADR:
  - `docs/05-governance/05-adr/ADR-012-permission-registry.md`

## Story–Sprint Eşleştirmeleri

| Story ID | Sprint ID | Not                                        |
|----------|-----------|--------------------------------------------|
| E08-S01  | (TBD)     | Permission registry tasarımı ve ilk entegrasyon |

## Bağımlılıklar

- Access modülü ve rota guard sözleşmeleri.
- Manifest/PageLayout (E04) ve observability (E06) tarafındaki metadata.

## Riskler

- Registry ile kod arasındaki drift; legacy izinlerin taşınmaması.
- Permission değişikliklerinin yanlış ortamlara release edilmesi.

## İlgili Artefaktlar

- `docs/05-governance/05-adr/ADR-012-permission-registry.md`

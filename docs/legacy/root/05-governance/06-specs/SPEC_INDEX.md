# SPEC_INDEX – Feature / Teknik Spec Dokümanları Özeti

Bu doküman, `docs/05-governance/06-specs/*.md` altındaki **SPEC** dokümanlarının durumunu tek yerde gösteren indeks tablosudur.  
`docs/05-governance/PROJECT_FLOW.md` Story akış görünümünü, `docs/05-governance/05-adr/ADR_INDEX.md` mimari kararları, SPEC_INDEX ise teknik tasarımları özetler.

---

## 1. Durum Özeti

- Toplam SPEC: **8** (şablonlar hariç)  
- Şablon / format dosyaları:
  - `MP-SPEC-FORMAT.md` – format MP’i (bu dosyadaki yapı doğrudan yeni SPEC’lere uygulanır)

---

## 2. SPEC Listesi

Dar ekranlarda daha okunabilir olsun diye tabloyu dört sütunla sınırladık; Story/ADR/yol bilgileri altta ayrı verilir.  
SPEC ID sütunu doğrudan dosyaya link verir (`[SPEC-ID](SPEC-<STORY-ID>-<kısa-konu>.md)`); dosya yolu ayrıca ayrı sütunda tekrar edilmez.

| SPEC ID                                           | Başlık                                      | Durum | Tarih       |
|--------------------------------------------------|----------------------------------------------|-------|------------|
| [SPEC-E01-S05-IDENTITY-AUTH-V1](SPEC-E01-S05-IDENTITY-AUTH-V1.md)                 | Identity Auth & Shell Login v1.0            | Draft | 2025-11-19 |
| [SPEC-E02-S02-GRID-REPORTING-V1](SPEC-E02-S02-GRID-REPORTING-V1.md)               | Grid & Reporting v1.0                       | Draft | 2025-11-19 |
| [SPEC-E03-S01-THEME-LAYOUT-V1](SPEC-E03-S01-THEME-LAYOUT-V1.md)                   | Theme & Shell Layout System v1.0            | Draft | 2025-11-19 |
| [SPEC-E04-S01-PLATFORM-MANIFEST-V1](SPEC-E04-S01-PLATFORM-MANIFEST-V1.md)         | Platform Manifest & Sözleşme v1.0           | Draft | 2025-11-19 |
| [SPEC-E05-S01-RELEASE-SAFETY-V1](SPEC-E05-S01-RELEASE-SAFETY-V1.md)               | Release Safety, Canary & DR Guardrail v1.0  | Draft | 2025-11-19 |
| [SPEC-E06-S01-TELEMETRY-OBSERVABILITY-V1](SPEC-E06-S01-TELEMETRY-OBSERVABILITY-V1.md) | Telemetry & Observability Korelasyonu v1.0 | Draft | 2025-11-19 |
| [SPEC-E07-S01-GLOBALIZATION-A11Y-V1](SPEC-E07-S01-GLOBALIZATION-A11Y-V1.md)       | Globalization & Accessibility v1.0          | Draft | 2025-11-19 |
| [SPEC-E08-S01-PERMISSION-REGISTRY-V1](SPEC-E08-S01-PERMISSION-REGISTRY-V1.md)     | Permission Registry v1.0                    | Draft | 2025-11-19 |
| [SPEC-QLTY-FE-KEYCLOAK-01-FRONTEND-KEYCLOAK-OIDC](SPEC-QLTY-FE-KEYCLOAK-01-FRONTEND-KEYCLOAK-OIDC.md) | Frontend Keycloak / OIDC Entegrasyonu v1.0 | Draft | 2025-11-29 |
| [SPEC-QLTY-BE-KEYCLOAK-JWT-01-BACKEND-KEYCLOAK-JWT](SPEC-QLTY-BE-KEYCLOAK-JWT-01-BACKEND-KEYCLOAK-JWT.md) | Backend Keycloak JWT Sertleştirmesi & Provisioning v1.0 | Draft | 2025-11-30 |

**Detaylar**

- `SPEC-E04-S01-PLATFORM-MANIFEST-V1`
  - Durum: Draft  
  - Tarih: 2025-11-19  
  - Story: `E04-S01-Manifest-Platform-v1`  
  - İlgili ADR’ler: `ADR-001`, `ADR-002`, `ADR-007`
  - Doküman Yolu: `docs/05-governance/06-specs/SPEC-E04-S01-PLATFORM-MANIFEST-V1.md`

- `SPEC-E05-S01-RELEASE-SAFETY-V1`
  - Durum: Draft  
  - Tarih: 2025-11-19  
  - Story: `E05-S01-Release-Safety-and-DR`  
  - İlgili ADR’ler: `ADR-006`, `ADR-009`, `ADR-010`, `ADR-013`
  - Doküman Yolu: `docs/05-governance/06-specs/SPEC-E05-S01-RELEASE-SAFETY-V1.md`

- `SPEC-E06-S01-TELEMETRY-OBSERVABILITY-V1`
  - Durum: Draft  
  - Tarih: 2025-11-19  
  - Story: `E06-S01-Telemetry-and-Observability`  
  - İlgili ADR’ler: `ADR-004`, `ADR-011`
  - Doküman Yolu: `docs/05-governance/06-specs/SPEC-E06-S01-TELEMETRY-OBSERVABILITY-V1.md`

- `SPEC-E07-S01-GLOBALIZATION-A11Y-V1`
  - Durum: Draft  
  - Tarih: 2025-11-19  
  - Story: `E07-S01-Globalization-and-Accessibility`  
  - İlgili ADR’ler: `ADR-008`, `ADR-014`
  - Doküman Yolu: `docs/05-governance/06-specs/SPEC-E07-S01-GLOBALIZATION-A11Y-V1.md`

- `SPEC-E01-S05-IDENTITY-AUTH-V1`
  - Durum: Draft  
  - Tarih: 2025-11-19  
  - Story: `E01-S05-Login`  
  - İlgili ADR’ler: `ADR-003`, `ADR-008`, `ADR-016`
  - Doküman Yolu: `docs/05-governance/06-specs/SPEC-E01-S05-IDENTITY-AUTH-V1.md`

- `SPEC-E02-S02-GRID-REPORTING-V1`
  - Durum: Draft  
  - Tarih: 2025-11-19  
  - Story: `E02-S02-Grid-UI-Kit-SSRM`  
  - İlgili ADR’ler: `ADR-005`, `ADR-015`
  - Doküman Yolu: `docs/05-governance/06-specs/SPEC-E02-S02-GRID-REPORTING-V1.md`

- `SPEC-E03-S01-THEME-LAYOUT-V1`
  - Durum: Draft  
  - Tarih: 2025-11-19  
  - Story: `E03-S01-Theme-Layout-System`  
  - İlgili ADR’ler: `ADR-016`
  - Feature Request: FR-005 – Theme & Shell Layout Figma Kit v1.0 (`docs/05-governance/FEATURE_REQUESTS.md`)
  - Doküman Yolu: `docs/05-governance/06-specs/SPEC-E03-S01-THEME-LAYOUT-V1.md`

- `SPEC-E08-S01-PERMISSION-REGISTRY-V1`
  - Durum: Draft  
  - Tarih: 2025-11-19  
  - Story: `E08-S01-Permission-Registry`  
  - İlgili ADR’ler: `ADR-012`
  - Doküman Yolu: `docs/05-governance/06-specs/SPEC-E08-S01-PERMISSION-REGISTRY-V1.md`

- `SPEC-QLTY-FE-KEYCLOAK-01-FRONTEND-KEYCLOAK-OIDC`
  - Durum: Draft  
  - Tarih: 2025-11-29  
  - Story: `QLTY-FE-KEYCLOAK-01`  
  - İlgili ADR’ler: `ADR-003`, `ADR-010`
  - Doküman Yolu: `docs/05-governance/06-specs/SPEC-QLTY-FE-KEYCLOAK-01-FRONTEND-KEYCLOAK-OIDC.md`

- `SPEC-QLTY-BE-KEYCLOAK-JWT-01-BACKEND-KEYCLOAK-JWT`
  - Durum: Draft  
  - Tarih: 2025-11-30  
  - Story: `QLTY-BE-KEYCLOAK-JWT-01`  
  - İlgili ADR’ler: `ADR-003`, `ADR-010`
  - Doküman Yolu: `docs/05-governance/06-specs/SPEC-QLTY-BE-KEYCLOAK-JWT-01-BACKEND-KEYCLOAK-JWT.md`

> Notlar:
> - “Durum” alanı henüz SPEC dosyalarında meta olarak tutulmuyor; bu dosyada manuel yönetilir (Draft / Accepted / Deprecated vb.).
> - Yeni SPEC eklendiğinde tabloya yeni satır ve aşağıya buna benzer bir “Detaylar” bloğu eklemek yeterlidir.

---

## 3. Kullanım Notları

- Yeni SPEC açıldığında:
  - Dosya `docs/05-governance/06-specs/SPEC-<STORY-ID>-<kısa-konu>.md` formatında oluşturulur (ör. `SPEC-E03-S02-theme-runtime-v1.md`).
  - Yapı ve meta: `MP-SPEC-FORMAT.md` içindeki örnek/meta bloğu uygulanır (ayrı şablon dosyası yok).
  - İlgili Story ve ADR’ler üst meta bölümündeki “İlgili Dokümanlar” alanında belirtilir.
  - Bu indeks dosyasına bir satır eklenir.

- SPEC kabul / revize edildiğinde:
  - Gerekirse “Durum” ve “Tarih” alanları bu tabloda güncellenir.
  - İlişkili Story / Acceptance dokümanlarındaki linkler senkron tutulur.

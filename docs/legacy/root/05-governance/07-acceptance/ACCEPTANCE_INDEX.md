# ACCEPTANCE_INDEX – Epic / Story / Proje Kabul Kayıtları Özeti

Bu doküman, `docs/05-governance/07-acceptance/*.md` altındaki acceptance dokümanlarının durumunu tek yerde gösteren indeks tablosudur.  
`docs/05-governance/PROJECT_FLOW.md` Story akış görünümünü, `docs/05-governance/05-adr/ADR_INDEX.md` mimari kararları, `docs/05-governance/06-specs/SPEC_INDEX.md` teknik tasarımları, ACCEPTANCE_INDEX ise kabul kriterlerini özetler.

---

## 1. Durum Özeti

- Toplam acceptance dokümanı: **22** (legacy + Story/Epic/ops/qa karışık)  
- Örnek statü dağılımı:
  - `pending`: çoğu Story / ops / qa acceptance
  - `in_progress`: ALPHA-03
  - `done`: ALPHA-04 vb.

> Not: Durum bilgisi acceptance dosyalarındaki frontmatter içindeki `status` alanından okunur.

---

## 2. Story Acceptance’ları

Dar ekranda okunabilirlik için tabloyu üç sütunla sınırladık.  
Story ID hücresinde yalnızca ID görünür ve acceptance dosyasına göreli link taşır (`[E0X-SYY](E0X-SYY-...acceptance.md)`); dosya adı satırda ikinci kez tekrar edilmez.

| Story (Acceptance)                               | Başlık Kısa                | Durum   |
|--------------------------------------------------|----------------------------|---------|
| [E01-S05](E01-S05-Login.acceptance.md)           | Login                      | pending |
| [E01-S10](E01-S10-Auth-BroadcastChannel-Full-MFE-Sync.acceptance.md) | Auth & BroadcastChannel   | done    |
| [E01-S90](E01-S90-Identity-Support.acceptance.md)| Identity Support           | done    |
| [E02-S01](E02-S01-AG-Grid-Standard.acceptance.md)| AG Grid Standard           | pending |
| [E02-S02](E02-S02-Grid-UI-Kit-SSRM.acceptance.md)| Grid UI Kit SSRM          | done    |
| [E03-S01](E03-S01-Theme-Layout-System.acceptance.md) | Theme & Layout System     | pending |
| [E03-S02](E03-S02-Theme-Runtime-Integration.acceptance.md) | Theme Runtime Integration | done    |
| [E03-S03](E03-S03-Theme-Overlay-And-Grid-Tone.acceptance.md) | Theme Overlay & Grid Tone | done    |
| [E03-S04](E03-S04-Header-Nav-Overflow.acceptance.md) | Header Navigasyon & Overflow | draft  |
| [E03-S05](E03-S05-Theme-Personalization.acceptance.md) | Theme Personalization & Custom Themes | ready |
| [E04-S01](E04-S01-Manifest-Platform-v1.acceptance.md) | Manifest Platform v1      | pending |
| [E05-S01](E05-S01-Release-Safety-and-DR.acceptance.md) | Release Safety & DR       | pending |
| [E06-S01](E06-S01-Telemetry-and-Observability.acceptance.md) | Telemetry & Observability | pending |
| [E07-S01](E07-S01-Globalization-and-Accessibility.acceptance.md) | Globalization & A11y     | pending |
| [E08-S01](E08-S01-Permission-Registry.acceptance.md) | Permission Registry       | pending |
| [QLTY-BE-KEYCLOAK-JWT-01](QLTY-BE-KEYCLOAK-JWT-01.acceptance.md) | Backend Keycloak JWT      | pending |
| [QLTY-FE-SPA-LOGIN-01](QLTY-FE-SPA-LOGIN-01.acceptance.md) | SPA Login Flow            | pending |
| [QLTY-API-V1-STANDARDIZATION-01](QLTY-API-V1-STANDARDIZATION-01.acceptance.md) | API v1 standardizasyonu  | pending |
| [QLTY-FE-SHARED-HTTP-01](QLTY-FE-SHARED-HTTP-01.acceptance.md) | Shared HTTP layer         | done    |
| [QLTY-REST-AUTH-01](QLTY-REST-AUTH-01.acceptance.md) | Auth REST/DTO v1          | done    |
| [QLTY-REST-USER-01](QLTY-REST-USER-01.acceptance.md) | User REST/DTO v1          | done    |
| [SEC-VAULT-FAILOVER-01](SEC-VAULT-FAILOVER-01.acceptance.md) | Vault fail-fast fallback  | in_progress |

**Detaylar**

- Tüm bu dosyalar `docs/05-governance/07-acceptance/` klasörü altında yer alır.  
- Her biri frontmatter’da en az şu alanlara sahiptir:
  - `title: "Acceptance — <StoryID> <Kısa Başlık>"`
  - `status: pending / done / in_progress`
  - `related_story: <StoryID>`

---

## 3. Proje / Epic / Diğer Acceptance’lar

Bu bölüm, Story dışı kabul dokümanlarını özetler (proje, tema, FE, ops, QA vb.); ID hem linkte hem “İlgili Ticket” sütununda tekrarlanmaz.

| Acceptance        | Tür      | Durum        |
|-------------------|----------|-------------|
| [ALPHA-01](ALPHA-01.md)  | Proje    | pending     |
| [ALPHA-03](ALPHA-03.md)  | Proje    | in_progress |
| [ALPHA-04](ALPHA-04.md)  | Proje    | done        |
| [FE-06](FE-06.md)        | Frontend | pending     |
| [OPS-02](OPS-02.md)      | Ops      | pending     |
| [QA-02](QA-02.md)        | QA       | pending     |

**Detaylar**

- Dosya yolları:
  - `docs/05-governance/07-acceptance/ALPHA-01.md`
  - `docs/05-governance/07-acceptance/ALPHA-03.md`
  - `docs/05-governance/07-acceptance/ALPHA-04.md`
  - `docs/05-governance/07-acceptance/FE-06.md`
  - `docs/05-governance/07-acceptance/OPS-02.md`
  - `docs/05-governance/07-acceptance/QA-02.md`
- Frontmatter alanları:
  - `title`, `status`, `owner`, `related_ticket` vb.

---

## 4. Kullanım Notları

- Yeni acceptance dokümanı eklendiğinde:
  - İlgili Story/Epic/Proje ile `related_story` veya `related_ticket` alanı üzerinden ilişkilendirilmelidir.
  - Bu indeks dosyasına Story acceptance veya Proje/Epic acceptance tablosuna bir satır eklenmelidir.

- Durum güncellemeleri:
  - Frontmatter’daki `status` alanı değiştirilirken bu tabloda da aynı şekilde güncellenmesi önerilir; böylece genel görünüm korunur.

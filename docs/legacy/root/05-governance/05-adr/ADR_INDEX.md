# ADR_INDEX – Architecture Decision Records Özeti

Bu doküman, `docs/05-governance/05-adr/*.md` altındaki tüm ADR’lerin durumunu tek yerde gösteren master listedir.  
`docs/05-governance/PROJECT_FLOW.md` Story akış görünümünü verirken, ADR_INDEX mimari kararların genel resmini sunar.

---

## 1. Durum Özeti

- Toplam ADR: **18**
- Durum dağılımı:
  - **Accepted:** 16
  - **Proposed:** 2 (örnek ADR dahil)
  - **Rejected / Deprecated / Superseded:** 0 (şu an yok)

---

## 2. ADR Listesi

Dar ekranda okunabilir olsun diye tabloyu dört sütunla sınırladık.  
ADR sütununda yalnız ADR ID görünür ve ilgili dosyaya göreli link taşır (`[ADR-XXX](ADR-XXX-...md)`); dosya yolu satırda ikinci kez tekrar edilmez.

| ADR                                           | Başlık                                          | Durum     | Tarih       |
|-----------------------------------------------|--------------------------------------------------|-----------|------------|
| [ADR-001](ADR-001-remote-manifest-and-sri.md) | Remote Manifest & SRI                            | Accepted  | 2025-11-02 |
| [ADR-002](ADR-002-page-layout-and-manifest-model.md) | PageLayout & Manifest Modeli               | Accepted  | 2025-11-02 |
| [ADR-003](ADR-003-auth-and-broadcast-channel.md) | Auth & BroadcastChannel Stratejisi             | Accepted  | 2025-11-02 |
| [ADR-004](ADR-004-telemetry-and-audit-taxonomy.md) | Telemetry & Audit Taksonomisi                 | Accepted  | 2025-11-02 |
| [ADR-005](ADR-005-ag-grid-standard-and-experience-budgets.md) | AG Grid Standardı & Deneyim Bütçeleri  | Accepted  | 2025-11-02 |
| [ADR-006](ADR-006-canary-and-rollback-strategy.md) | Canary & Rollback Stratejisi                  | Accepted  | 2025-11-02 |
| [ADR-007](ADR-007-contract-and-schema-gating.md) | Contract & Schema Gating                       | Accepted  | 2025-11-02 |
| [ADR-008](ADR-008-i18n-and-dictionary-packaging.md) | i18n & Sözlük Paketleme                     | Accepted  | 2025-11-02 |
| [ADR-009](ADR-009-feature-flag-governance.md) | Feature Flag Yönetişimi                        | Accepted  | 2025-11-02 |
| [ADR-010](ADR-010-security-pipeline.md)       | Güvenlik Zinciri (CI/CD)                       | Accepted  | 2025-11-02 |
| [ADR-011](ADR-011-observability-correlation.md) | Observability Korelasyonu                    | Accepted  | 2025-11-02 |
| [ADR-012](ADR-012-permission-registry.md)     | Permission Registry                            | Accepted  | 2025-11-02 |
| [ADR-013](ADR-013-dr-ha-and-edge-strategy.md) | DR / HA & Edge Stratejisi                      | Accepted  | 2025-11-02 |
| [ADR-014](ADR-014-accessibility-process-standard.md) | Erişilebilirlik Süreç Standardı            | Accepted  | 2025-11-02 |
| [ADR-015](ADR-015-grid-mimarisi-ui-kit-ssrm.md) | Grid Mimarisi (UI Kit + SSRM)                | Accepted  | 2025-11-03 |
| [ADR-016](ADR-016-theme-layout-system.md)     | Theme & Layout System v1.0                     | Accepted  | 2025-11-15 |
| [ADR-019](ADR-019-theme-ssot.md)              | Theme Single Source of Truth                   | Proposed  | 2025-12-06 |
| [ADR-DOCS-018*](ADR-018-sample-adr-format.md) | Örnek ADR (MP-ADR-FORMAT Uygulaması)           | Proposed  | 2025-11-19 |

\* ADR-DOCS-018, yalnızca `docs/05-governance/05-adr/MP-ADR-FORMAT.md` kullanımını göstermek için eklenmiş örnek dokümandır;  
gerçek bir mimari kararı temsil etmez ve durum analizi yapılırken ayrı değerlendirilmelidir.

---

## 3. Kullanım Notları

- Yeni ADR eklendiğinde:
  - Dosya `docs/05-governance/05-adr/ADR-0XX-...md` altında oluşturulur ve `MP-ADR-FORMAT.md` yapısına uyar.
  - Bu tabloya yeni satır eklenir (ID, başlık, durum, tarih, yol).
- ADR durumu değiştiğinde (Proposed → Accepted vb.):
  - Hem ADR dosyasındaki `Durum (Status)` alanı hem de bu tablodaki karşılık gelen satır güncellenir.
- Yüksek seviye izleme için:
  - Hızlı “hangi kararlar var, kaçı Accepted?” sorusu bu sayfa üzerinden cevaplanır.

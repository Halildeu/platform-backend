# ADR-007 – Contract & Schema Gating

**Durum (Status):** Accepted  
**Tarih:** 2025-11-02  

**İlgili Dokümanlar (Traceability):**
- SPEC: docs/05-governance/06-specs/SPEC-E04-S01-PLATFORM-MANIFEST-V1.md
- ACCEPTANCE: docs/05-governance/07-acceptance/E04-S01-Manifest-Platform-v1.acceptance.md
- STORY: docs/05-governance/02-stories/E04-S01-Manifest-Platform-v1.md
- İlgili ADR’ler: ADR-001, ADR-002, ADR-003
- STYLE GUIDE: (Varsa STYLE-API-001)

---

# 1. Bağlam (Context)

- Manifest (ADR-001/002) ve ShellServices sözleşmeleri runtime’da güvenilir çalışmak için tutarlı şemalara bağlı. 
- Şema uyumsuzlukları ancak dağıtımdan sonra fark edilirse kullanıcıyı kesiyor.
- CI pipeline’ında otomatik doğrulama yoksa manifest/sözleşme değişiklikleri gözden kaçabiliyor.

---

# 2. Karar (Decision)

- Manifest ve ShellServices için JSON Schema / TypeScript tip sözleşmeleri oluşturulacak; repo içinde versiyonlanacak.
- CI/CD pipeline’ında contract test step’i zorunlu olacak. Remote veya shell build’i, şema uyumsuzluğu durumunda fail edecek.
- Breaking değişiklikler semver major gerektirecek ve manifest sürüm aralığı buna göre güncellenecek.
- Şema paketleri (npm/verdaccio) olarak yayınlanacak; remotelar build-time’da bu tipleri kullanacak.

---

# 3. Alternatifler (Alternatives)

- Yalnız runtime validasyon (deployment sonrası hata yakalama)
- Tip/şema olmadan “best effort” entegrasyon

Bu alternatifler hata maliyeti yüksek olduğu için tercih edilmemiştir.

---

# 4. Gerekçeler (Rationale)

- Contract-first yaklaşım, manifest ve servis değişikliklerinin etkisini CI aşamasında yakalamayı sağlar.
- Versiyonlanmış şema paketleri, FE/BE ekipleri arasında net bir sözleşme ortaya koyar.

---

# 5. Sonuçlar (Consequences)

- Yeni alan eklemek veya mevcut alanı değiştirmek için schema güncellemesi + semver gerekecek.
- CI pipeline’ında extra contract test süresi ( birkaç saniye ) eklenecek.
- İleride schema büyüdükçe versiyon yönetimi dikkatle yapılmalı; backward compatibility analizi şart.

### Acceptance / Metrics

- Contract test step’i olmadan hiçbir MFE/shell build’i merge edilmiyor.
- Schema uyumsuzlukları CI’da yakalanıyor; production’da manifest sözleşme hatası görülmüyor.
- Şema paketinin semver logu Backstage veya dokümantasyonda erişilebilir.

---

# 6. Uygulama Detayları (Implementation Notes)

- Şema paketlerinin isimlendirilmesi ve versiyonlama stratejisi (örn. `@app/manifest-schema`) net olmalıdır.
- Contract test adımları CI pipeline şablonlarında ortaklaştırılmalıdır.

---

# 7. Durum Geçmişi (Status History)

| Tarih       | Durum    | Not                                   |
|-------------|----------|----------------------------------------|
| 2025-11-02  | Accepted | İlk sürüm, ADR-007 kararı kabul edildi |

---

# 8. Notlar

- Schema gating stratejisi, ileride GraphQL veya farklı API katmanları eklendiğinde güncellenebilir.

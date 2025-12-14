# ADR-010 – Güvenlik Zinciri (CI/CD)

**Durum (Status):** Accepted  
**Tarih:** 2025-11-02  

**İlgili Dokümanlar (Traceability):**
- SPEC: `docs/05-governance/06-specs/SPEC-E05-S01-RELEASE-SAFETY-V1.md`
- ACCEPTANCE: `docs/05-governance/07-acceptance/E05-S01-Release-Safety-and-DR.acceptance.md`
- STORY: `docs/05-governance/02-stories/E05-S01-Release-Safety-and-DR.md`
- İlgili ADR’ler: ADR-001, ADR-006
- STYLE GUIDE: (Varsa STYLE-SEC-001)

---

# 1. Bağlam (Context)

- Manifest/SRI (ADR-001) ve canary stratejisi (ADR-006) güvenlik açısından güçlü, ancak build & deployment pipeline’larında güvenlik taraması eksik.
- Düzenli SAST/DAST, bağımlılık taraması ve artefact imzası olmadan tedarik zinciri riskleri devam ediyor.

---

# 2. Karar (Decision)

- CI pipeline’larına SAST (örn. SonarQube) ve DAST (OWASP ZAP) adımları ekleniyor.
- Bağımlılık taraması: Trivy veya OWASP Dependency-Check; SBOM çıkarımı ve cosign ile imzalı artefact üretimi. 
- CSP’yi `report-only` modda başlatıp ihlal gözlemlendikten sonra enforce edeceğiz; SRI hash rotasyonu (per release) zorunlu.
- CI pipeline’ı fail olduğunda deploy bloke edilecek; güvenlik raporları pipeline artefact’ı olarak saklanacak.

---

# 3. Alternatifler (Alternatives)

- Sadece manuel güvenlik testleri ile ilerlemek
- Sadece bağımlılık taraması yapmak (SAST/DAST olmadan)

Bu yaklaşımlar riskleri yeterince azaltmadığı için tercih edilmemiştir.

---

# 4. Gerekçeler (Rationale)

- Otomatik SAST/DAST ve dependency taraması, supply chain risklerini erken aşamada yakalar.
- SBOM ve imzalı artefact’lar, denetlenebilirlik ve geri izlenebilirlik sağlar.

---

# 5. Sonuçlar (Consequences)

- Pipeline süreleri birkaç dakika uzayabilir; DevOps kapasitesi ayarlanmalı.
- Güvenlik raporlarının analiz edilmesi için Security ekibiyle süreç planlanmalı.
- Cosign/SLSA entegrasyonu için ek kimlik yönetimi (kaynak imza anahtarları) gerekiyor.
- GitHub Actions üzerinde `security-guardrails.yml` workflow’u SAST/DAST + SBOM guardrail’lerini enforce eder; sonuç raporları `security-reports/` klasörü altında artefact olarak arşivlenir.

### Acceptance / Metrics

- SAST/DAST adımlarından kritik/major riskler zero build ile release olmaz. 
- SBOM verisi ve imzalı artefact’lar arşivlenir; supply chain audit’e hazır.
- CSP violation raporları 0’a düşene kadar `report-only`, sonra enforce; SRI hash yenilemesi her release’de doğrulanır.

---

# 6. Uygulama Detayları (Implementation Notes)

- SAST/DAST araçlarının konfigürasyonu merkezi shared pipeline şablonlarında tutulmalıdır.
- SBOM üretimi ve imza adımları için standart script’ler tanımlanmalıdır.

---

# 7. Durum Geçmişi (Status History)

| Tarih       | Durum    | Not                                   |
|-------------|----------|----------------------------------------|
| 2025-11-02  | Accepted | İlk sürüm, ADR-010 kararı kabul edildi |

---

# 8. Notlar

- Gelecekte SLSA seviyeleri yükseltildiğinde bu ADR güncellenebilir.

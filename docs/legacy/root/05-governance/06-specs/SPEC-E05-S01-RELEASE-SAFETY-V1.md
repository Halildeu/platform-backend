# SPEC-E05-S01-RELEASE-SAFETY-V1
**Başlık:** Release Safety, Canary & DR Guardrail v1.0  
**Versiyon:** v1.0  
**Tarih:** 2025-11-19  

**İlgili Dokümanlar:**  
- EPIC: `docs/05-governance/01-epics/E05_ReleaseSafetyAndSecurityPipeline.md`  
- ADR:  
  - `docs/05-governance/05-adr/ADR-006-canary-and-rollback-strategy.md`  
  - `docs/05-governance/05-adr/ADR-009-feature-flag-governance.md`  
  - `docs/05-governance/05-adr/ADR-010-security-pipeline.md`  
  - `docs/05-governance/05-adr/ADR-013-dr-ha-and-edge-strategy.md`  
- ACCEPTANCE: `docs/05-governance/07-acceptance/E05-S01-Release-Safety-and-DR.acceptance.md`  
- STORY: `docs/05-governance/02-stories/E05-S01-Release-Safety-and-DR.md`  
- STYLE GUIDE: `docs/00-handbook/NAMING.md`  

**Etkilenen Modüller / Servisler:**  

| Modül/Servis  | Açıklama / Sorumluluk                            | İlgili ADR |
|---------------|--------------------------------------------------|------------|
| ops-ci        | security-guardrails, canary pipeline orkestrasyonu| ADR-010    |
| platform-ops  | DR/HA ve kill-switch runbook’ları                | ADR-013    |
| frontend-shell| Feature flag / kill-switch UI + Unleash entegr.  | ADR-009    |
| api-gateway   | Canary traffic yönlendirmesi ve rollback tetikleri| ADR-006    |
| observability | Guardrail metriklerinin ölçümü ve alarmı         | ADR-006    |

---

# 1. Amaç (Purpose)

Canary rollout stratejisini (ADR-006), feature flag yönetişimini (ADR-009), güvenlik pipeline’ını (ADR-010) ve DR/HA & edge stratejisini (ADR-013) tek bir teknik tasarım altında birleştirmek.  

Bu spesifikasyon, manifest tabanlı platform için:
- Canary yayın adımlarının nasıl tanımlanacağını ve guardrail’lerin nasıl uygulanacağını,
- Feature flag adlandırma ve yaşam döngüsünün nasıl standardize edileceğini,
- CI/CD güvenlik zincirinde hangi adımların zorunlu olacağını,
- DR/HA ve edge konfigürasyonlarının release hattına nasıl bağlanacağını
tarif eder.

---

# 2. Kapsam (Scope)

### Kapsam içi
- Argo CD/Harbor temelli canary rollout adımlarının tanımı (10/50/100 trafik yüzdeleri).
- Unleash feature flag naming + lifecycle standardının uygulanması.
- Canary guardrail metriklerinin seçimi, ölçümü ve ihlal durumunda rollback akışı.
- CI pipeline’ında SAST/DAST, dependency taraması, SBOM üretimi ve imzalı artefact adımları.
- DR/HA & edge konfigürasyonlarının (manifest/sözlük servisleri için) canary + rollback akışına entegrasyonu.

### Kapsam dışı
- Tek tek servisler için ayrıntılı DR runbook’ları (ayrı ops Story’lerinde ele alınır).
- Tüm mevcut flag’lerin borç temizliği (bu SPEC yeni standardı tarif eder; temizlik için ayrı işler açılır).

---

# 3. Tanımlar (Definitions)

- **Canary Release:** Trafiğin küçük bir yüzdesini yeni versiyona yönlendirerek riskli değişiklikleri kademeli devreye alma stratejisi.  
- **Guardrail:** Canary sırasında izlenen, ihlalde otomatik rollback tetikleyen metrik seti (TTFA, hata oranı, Sentry error vb.).  
- **Feature Flag:** Bir özelliğin runtime’da açılıp kapatılmasını sağlayan kontrol mekanizması.  
- **Kill-Switch:** Kritik durumda ilgili özelliği veya modülü anında devre dışı bırakmak için kullanılan flag.  
- **DR/HA:** Disaster Recovery / High Availability; çoklu bölge, failover ve fallback stratejilerini kapsar.  
- **SBOM:** Software Bill of Materials; build edilen artefact içindeki bağımlılıkların listesi.  

---

# 4. Kullanıcı Senaryoları (User Flows)

1. **Canary Rollout (10 → 50 → 100)**
   - DevOps, release pipeline’ını tetikler; ilk aşamada trafiğin %10’u yeni versiyona yönlenir.
   - Guardrail metrikleri (TTFA, hata oranı, Sentry error rate) 30 dakika boyunca izlenir.
   - Metrikler yeşil ise trafik %50’ye çıkar; süreç tekrarlanır.
   - Son aşamada trafik %100’e alınır; canary tamamlanır.

2. **Guardrail İhlali ve Otomatik Rollback**
   - Canary sırasında guardrail metriklerinden biri eşik üstüne çıkar (örneğin hata oranı > %2).
   - Pipeline’daki canary job’ı rollback adımını tetikler:
     - Argo CD eski manifest revizyonuna döner.
     - İlgili feature flag kill‑switch default kapalıya alınır.
   - Ops ekibine ve ilgili chat kanalına otomatik uyarı düşer.

3. **Feature Flag Lifecycle Yönetimi**
   - Yeni bir flag `{domain}:{feature}:{variant}` formatında oluşturulur.
   - Canary ve GA aşamalarında kullanım telemetrisi toplanır (aktif kullanıcı sayısı, hata oranı).
   - Flag etkisi stabil olduğunda “GA” durumuna alınır; belirlenen süre sonunda bir “retire” kararı verilir.

4. **CI Güvenlik Zinciri**
   - Pipeline’ın build aşamasında SAST ve dependency taraması çalıştırılır; kritik bulgu varsa pipeline kırmızı kalır.
   - SBOM üretilir ve artefact imzalanır.
   - DAST veya smoke test ortamında temel güvenlik kontrolleri yapılır.

5. **DR Drill**
   - Planlı DR testi sırasında manifest/sözlük servisleri için simüle edilmiş bir outage senaryosu çalıştırılır.
   - Trafik, edge/CDN ve manifest konfigürasyonları üzerinden alternative zone’a yönlendirilir.
   - Drill sonunda metrikler ve sürecin başarı/başarısızlık notları dokümante edilir.

---

# 5. Fonksiyonel Gereksinimler (Functional Requirements)

**FR-REL-01:** Canary rollout üç aşamalı olmalıdır: %10 → %50 → %100; her aşama için en az 30 dakikalık stabil gözlem penceresi bulunmalıdır.  
**FR-REL-02:** Guardrail metrikleri (TTFA, hata oranı, Sentry error oranı) canary aşamalarında otomatik ölçülmeli; eşik ihlalinde rollback tetiklenmelidir.  
**FR-REL-03:** Feature flag’ler `{domain}:{feature}:{variant}` formatında adlandırılmalı ve lifecycle kayıtları (create → canary → GA → retire) tutulmalıdır.  
**FR-REL-04:** CI pipeline’ında SAST, dependency taraması, SBOM üretimi ve artefact imzası adımları zorunlu olmalıdır; kritik bulguda release engellenmelidir.  
**FR-REL-05:** DR drill senaryolarında manifest ve sözlük servisleri için otomatik failover < 1 dakika içinde gerçekleşmelidir.  

---

# 6. İş Kuralları (Business Rules)

**BR-REL-01:** Canary aşamaları manuel olarak atlanamaz; traffic weight ancak guardrail metrikleri yeşil olduğunda bir üst seviyeye çıkar.  
**BR-REL-02:** Kill‑switch flag’ler her ortamda (dev/stage/prod) hazır olmalı ve rollback durumunda otomatik kapalıya alınmalıdır.  
**BR-REL-03:** Kritik güvenlik bulgusu (`severity=critical`) varken hiçbir ortam için release tamamlanamaz; ancak fix veya conscious override ile ilerlenebilir.  
**BR-REL-04:** DR drill’leri, yılda en az bir kez prod veya production‑benzeri ortamda tekrarlanmalı ve sonuçları runbook’a işlenmelidir.  

---

# 7. Veri Modeli (Data Model)

## 7.1. Canary Konfigürasyonu (Örnek YAML)

```yaml
canary:
  steps:
    - weight: 10
      minDurationMinutes: 30
    - weight: 50
      minDurationMinutes: 30
    - weight: 100
      minDurationMinutes: 30
  guardrails:
    ttfb_p95_ms: 2000
    error_rate_pct: 2.0
    sentry_error_rate_pct: 1.0
```

## 7.2. Feature Flag Kayıt Modeli (Özet)

| Alan         | Tip     | Zorunlu | Açıklama                                |
|--------------|---------|---------|-----------------------------------------|
| key          | string  | Evet    | `{domain}:{feature}:{variant}`          |
| environment  | string  | Evet    | dev/stage/prod                          |
| state        | string  | Evet    | off/canary/ga/retired                   |
| createdAt    | string  | Evet    | ISO tarih                               |
| retiredAt    | string  | Hayır   | ISO tarih (varsa)                       |
| owner        | string  | Hayır   | Sorumlu ekip/kullanıcı                  |

---

# 8. API Tanımı (API Spec)

Bu SPEC yeni dış API tanımı getirmez; canary/flag/DR orkestrasyonu Argo CD, Unleash ve CI araçları üzerinden çalışır.  
Gerektiğinde internal admin API’leri aşağıdaki gibi sade tutulur:

## 8.1. Örnek Admin Endpoint – Canary Durumu

- Method: `GET`  
- Path: `/internal/release/canary/status?service=<name>`  
- Auth: Internal (ops only)  
- Response:

```json
{
  "service": "mfe-shell",
  "currentWeight": 50,
  "step": 2,
  "status": "in_progress",
  "guardrails": {
    "ttfb_p95_ms": 1800,
    "error_rate_pct": 1.2,
    "sentry_error_rate_pct": 0.6
  }
}
```

---

# 9. Validasyon Kuralları (Validation Rules)

- Canary step weight değerleri 0–100 aralığında olmalı ve toplamda 100’e ulaşmalıdır.  
- Guardrail eşik değerleri, ADR-006’da tanımlanan sınırlar içinde konfigüre edilmelidir.  
- Feature flag key’leri regex ile doğrulanmalıdır: `^[a-z0-9]+:[a-z0-9-]+:[a-z0-9-]+$`.  
- SBOM ve imza adımları başarısız olduğunda pipeline kırmızı kalmalı; override ancak bilinçli onay ile yapılmalıdır.  

---

# 10. Hata Kodları (Error Codes)

| Kod                   | HTTP | Açıklama                                         |
|-----------------------|------|--------------------------------------------------|
| CANARY_GUARDRAIL_HIT  | 409  | Guardrail metrikleri ihlal edildi; rollback tetiklendi |
| FLAG_NAMING_INVALID   | 400  | Feature flag anahtarı naming standardına uymuyor |
| SECURITY_SCAN_FAILED  | 500  | SAST/DAST veya dependency taraması başarısız     |

---

# 11. Non-Fonksiyonel Gereksinimler (NFR)

Performans:
- Canary + güvenlik adımları pipeline süresini kabul edilebilir seviyede tutmalıdır (hedef ek süre birkaç dakikayı geçmemeli).

Operasyon:
- Rollback akışı, ops ekipleri için tekrarlanabilir ve runbook’larda yazılı olmalıdır.
- DR drill sonuçları kayıt altına alınmalı ve iyileştirme aksiyonları belirlenmelidir.

Güvenlik:
- Kritik bulgu tespit edilirken release gerçekleşmemelidir.
- SBOM ve imza süreçleri supply‑chain risklerini azaltacak şekilde zorunlu tutulmalıdır.

---

# 12. İzlenebilirlik (Traceability)

Bu spekten türeyen dokümanlar:
- Story: `docs/05-governance/02-stories/E05-S01-Release-Safety-and-DR.md`  
- Acceptance: `docs/05-governance/07-acceptance/E05-S01-Release-Safety-and-DR.acceptance.md`  
- ADR:  
  - `docs/05-governance/05-adr/ADR-006-canary-and-rollback-strategy.md`  
  - `docs/05-governance/05-adr/ADR-009-feature-flag-governance.md`  
  - `docs/05-governance/05-adr/ADR-010-security-pipeline.md`  
  - `docs/05-governance/05-adr/ADR-013-dr-ha-and-edge-strategy.md`  

Bu doküman, release safety & security pipeline v1.0 için teknik tasarımın tek kaynağıdır.

# ADR-013 – DR / HA & Edge Stratejisi

**Durum (Status):** Accepted  
**Tarih:** 2025-11-02  

**İlgili Dokümanlar (Traceability):**
- SPEC: `docs/05-governance/06-specs/SPEC-E05-S01-RELEASE-SAFETY-V1.md`
- ACCEPTANCE: `docs/05-governance/07-acceptance/E05-S01-Release-Safety-and-DR.acceptance.md`
- STORY: `docs/05-governance/02-stories/E05-S01-Release-Safety-and-DR.md`
- İlgili ADR’ler: ADR-001, ADR-006, ADR-008
- STYLE GUIDE: (Varsa STYLE-OPS-001)

---

# 1. Bağlam (Context)

- Manifest ve sözlük servisleri tek bölgede barındırılırsa canary/rollback (ADR-006) anlamını yitirir; DR planı yoksa outage durumunda platform çalışamaz.
- Fallback olmadan sözlük/manifest hatalarında UI eksik kalıyor.

---

# 2. Karar (Decision)

- Manifest ve sözlük artefact’ları multi-AZ (örn. iki farklı bölgede) barındırılacak. CDN/NGINX katmanı ile bölge hatalarında fallback devreye girecek.
- Sözlük/manifest için son başarılı sürüme otomatik dönüş mekanizması (version pinning) sağlanacak; hatalı release’te kullanıcı impact olmadan rollback.
- CDN cache politikası: Manifest kısa TTL (örn. 1 dk), sözlük orta TTL (15 dk). Gateway’de health check ve degrade mod ayarları yapılacak.
- DR tatbikatı (failover drill) belirli periyotlarla yapılacak; sonuçlar runbook’a işlenecek.

---

# 3. Alternatifler (Alternatives)

- Tek bölge (single region) deployment
- Manuel failover ve rollback

Bu alternatifler kullanılabilirlik ve kurtarma süreleri açısından yetersizdir.

---

# 4. Gerekçeler (Rationale)

- Multi-AZ ve otomatik fallback, kullanıcı etkisini minimize eder.
- Versiyon pinning, hatalı sürümlerde hızlı ve güvenli dönüş sağlar.

---

# 5. Sonuçlar (Consequences)

- Multi-AZ barındırma maliyeti ve ops yükü artacak.
- CDN/NGINX konfigürasyonları (header, cache, failover) yönetilmeli.
- Fallback sürüm yönetimi (versiyon pin) ekstra pipeline adımı gerektirebilir.

### Acceptance / Metrics

- Failover drill’lerinde manifest/sözlük servisi kesintisiz çalışıyor (kullanıcı etkilemeden). 
- Outage olduğunda otomatik fallback süresi < 1 dk.
- Runbook’ta DR prosedürü güncel; raporları denetimde sunulabilir.

---

# 6. Uygulama Detayları (Implementation Notes)

- CDN/NGINX konfigürasyonları versiyonlanmalı ve test ortamlarında doğrulanmalıdır.
- DR tatbikatları için periyodik plan ve raporlama süreci tanımlanmalıdır.

---

# 7. Durum Geçmişi (Status History)

| Tarih       | Durum    | Not                                   |
|-------------|----------|----------------------------------------|
| 2025-11-02  | Accepted | İlk sürüm, ADR-013 kararı kabul edildi |

---

# 8. Notlar

- DR runbook’ları `docs/04-operations/01-runbooks/` altında güncel tutulmalıdır.

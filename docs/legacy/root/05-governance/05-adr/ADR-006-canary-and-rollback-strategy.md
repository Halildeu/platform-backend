# ADR-006 – Canary & Rollback Stratejisi

**Durum (Status):** Accepted  
**Tarih:** 2025-11-02  

**İlgili Dokümanlar (Traceability):**
- SPEC: `docs/05-governance/06-specs/SPEC-E05-S01-RELEASE-SAFETY-V1.md`
- ACCEPTANCE: `docs/05-governance/07-acceptance/E05-S01-Release-Safety-and-DR.acceptance.md`
- STORY: `docs/05-governance/02-stories/E05-S01-Release-Safety-and-DR.md`
- İlgili ADR’ler: ADR-001, ADR-006, ADR-009, ADR-010, ADR-013
- STYLE GUIDE: (Varsa STYLE-OPS-001)

---

# 1. Bağlam (Context)

- Mikro-frontend dağıtımları bağımsız gerçekleşiyor; uyumsuzluklar kullanıcıları hızla etkileyebiliyor.
- Fail-closed manifest stratejisi olsa da kademeli trafik yönlendirme ve hızlı geri dönüş gereksinimi var.

---

# 2. Karar (Decision)

- Argo CD + Harbor ile canary yayın: trafik ağırlıkları %10 → %50 → %100; her adım en az 30 dakika stabil veri gerektiriyor.
- Unleash feature flag'leri ile modül ve sayfa seviyesinde kill-switch sağlanacak; canary aşamalarında varsayılan kapalı.
- Guardrail metrikleri: TTFA artışı, hatalı mutasyon oranı, Sentry hata eşiği. Guardrail tetiklenirse otomatik rollback < 5 dakika içinde yapılacak.
- Manifest sürümleri canary sırasında ayrı tutulacak; fail-closed olduğu durumda eski manifest devreye alınacak.

---

# 3. Alternatifler (Alternatives)

- Blue/Green deployment
- Direkt full rollout (canary olmadan)

Bu seçenekler risk ve gözlemlenebilirlik açıları nedeniyle tercih edilmemiştir.

---

# 4. Gerekçeler (Rationale)

- Canary dağıtımı, hatalı release’lerin etkisini sınırlayarak risk yönetimini iyileştirir.
- Feature flag ve guardrail metrikleri, karar süreçlerini otomatikleştirir.
- Manifest sürümlerinin ayrılması, hızlı rollback’i kolaylaştırır.

---

# 5. Sonuçlar (Consequences)

- Gözlem panoları (Grafana/Sentry) canary aşamalarını izleyecek şekilde yapılandırılmalı.
- Unleash flag yaşam döngüsü (adlandırma, ortam default'u, kapatma) yönetilmeli.
- Rollback prosedürü için runbook ve tatbikat takvimi hazırlanmalı.

### Acceptance / Metrics

- Canary aşaması 30 dakika stabil kaldığında bir üst ağırlığa otomatik geçiş.
- Rollback süre hedefi < 5 dakika; tatbikatlarda doğrulanmalı.
- Guardrail metrikleri (TTFA, hata oranı, Sentry) otomatik alarm üretiyor olmalı.

---

# 6. Uygulama Detayları (Implementation Notes)

- Argo CD ve Harbor konfigürasyonları canary adımlarını ve rollback koşullarını açıkça tanımlamalıdır.
- Unleash flag yönetimi için naming ve lifecycle rehberi (ADR-009) uygulanmalıdır.

---

# 7. Durum Geçmişi (Status History)

| Tarih       | Durum    | Not                                   |
|-------------|----------|----------------------------------------|
| 2025-11-02  | Accepted | İlk sürüm, ADR-006 kararı kabul edildi |

---

# 8. Notlar

- DR/HA ve edge stratejisi ADR-013 ile birlikte düşünülmelidir.

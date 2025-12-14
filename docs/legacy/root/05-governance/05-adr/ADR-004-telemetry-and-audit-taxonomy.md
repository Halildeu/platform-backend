# ADR-004 – Telemetry & Audit Taksonomisi

**Durum (Status):** Accepted  
**Tarih:** 2025-11-02  

**İlgili Dokümanlar (Traceability):**
- SPEC: `docs/05-governance/06-specs/SPEC-E06-S01-TELEMETRY-OBSERVABILITY-V1.md`
- ACCEPTANCE: `docs/05-governance/07-acceptance/E06-S01-Telemetry-and-Observability.acceptance.md`
- STORY: `docs/05-governance/02-stories/E06-S01-Telemetry-and-Observability.md`
- İlgili ADR’ler: ADR-011 (Observability Korelasyonu)
- STYLE GUIDE: (Varsa STYLE-BE-001 / STYLE-OBS-001)

---

# 1. Bağlam (Context)

- Manifest tabanlı sayfaların etkisini ölçmek ve denetlenebilirliği sağlamak için telemetri standartlarına ihtiyaç var.
- Mutasyonların audit sistemine bağlanması gereksiz tekrarlarla manuel bırakılmış durumda.
- KVKK / GDPR uyumu için PII redaction ve veri saklama kurallarını entegre etmeliyiz.

---

# 2. Karar (Decision)

- Temel event taksonomisi: `page_view`, `action_click`, `mutation_commit`. Boyutlar: `pageId`, `entity`, `result`, `duration`. 
- TTFA (Time To First Action) hem soğuk hem sıcak başlamada zorunlu ölçülecek.
- Tüm mutasyon yanıtları `auditId` döndürecek; notification center ve detail drawer bu ID ile audit kaydına bağlanacak.
- Telemetry pipeline'ında PII redaction uygulanacak; retention ve legal hold kuralları dokümante edilecek.

---

# 3. Alternatifler (Alternatives)

Bu ADR’de alternatif event taksonomileri detaylı olarak listelenmemiştir.  
Gelecekte gerekli olursa farklı event isimlendirme stratejileri burada karşılaştırılabilir.

---

# 4. Gerekçeler (Rationale)

- Standart bir event taksonomisi, tüm modüllerde ölçüm ve denetim kabiliyetini artırır.
- TTFA ve audit entegrasyonu, kullanıcı deneyimi ve regülasyon uyumu için kritiktir.
- PII redaction ve retention kuralları, KVKK / GDPR gibi regülasyonlara uyumu destekler.

---

# 5. Sonuçlar (Consequences)

- Telemetry şeması versiyonlanacak; event alanı değişiklikleri semver kurallarına tabi olacak.
- Telemetry örnekleme oranı yönetilmeli, aksi halde veri hacmi artabilir.
- Gözlemlenebilirlik panolarını (Grafana/Loki/Tempo) sürekli güncel tutmak gerekiyor.

### Acceptance / Metrics

- TTFA: soğuk ≤ 8 sn, sıcak ≤ 3 sn (p95).
- Audit link tıklanabilirliği ≥ %90 (notification → audit sayfası).
- Eksik çeviri / fallback oranı telemetry ile izlenmeli; hedef < %1.

---

# 6. Uygulama Detayları (Implementation Notes)

- Telemetry şeması ve event isimleri repo içinde versiyonlanmalıdır.
- Audit sistemine entegrasyon, `auditId` üzerinden uçtan uca test edilmelidir.

---

# 7. Durum Geçmişi (Status History)

| Tarih       | Durum    | Not                                   |
|-------------|----------|----------------------------------------|
| 2025-11-02  | Accepted | İlk sürüm, ADR-004 kararı kabul edildi |

---

# 8. Notlar

- Observability korelasyon detayları ADR-011’de genişletilmiştir.

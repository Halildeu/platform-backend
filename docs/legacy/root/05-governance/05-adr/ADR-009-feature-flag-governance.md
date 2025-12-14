# ADR-009 – Feature Flag Yönetişimi

**Durum (Status):** Accepted  
**Tarih:** 2025-11-02  

**İlgili Dokümanlar (Traceability):**
- SPEC: `docs/05-governance/06-specs/SPEC-E05-S01-RELEASE-SAFETY-V1.md`
- ACCEPTANCE: `docs/05-governance/07-acceptance/E05-S01-Release-Safety-and-DR.acceptance.md`
- STORY: `docs/05-governance/02-stories/E05-S01-Release-Safety-and-DR.md`
- İlgili ADR’ler: ADR-006 (Canary & Rollback)
- STYLE GUIDE: (Varsa STYLE-OPS-001)

---

# 1. Bağlam (Context)

- Canary / rollback stratejisi (ADR-006) Unleash feature flag’lerine dayanıyor.
- Flag adlandırması, yaşam döngüsü ve telemetry olmadan flag borcu artıyor; kademeli rollout kontrolsüzleşiyor.

---

# 2. Karar (Decision)

- Unleash’te flag adlandırma standardı: `{domain}:{feature}:{variant}` formatı.
- Flag yaşam döngüsü: create → canary → GA → retire. Her geçiş için approval kaydı Backstage notlarında tutulacak.
- Ortam bazlı varsayılanlar (dev/stage/prod) tanımlanacak; kill-switch flag’ler hazır olacak.
- Flag telemetry: Flag açan kullanıcı, etkilediği event sayısı, kabul oranı. Telemetry dashboardu ile izlenecek.

---

# 3. Alternatifler (Alternatives)

- Flag adlandırmasını serbest bırakmak (naming kuralı olmadan)
- Telemetry toplamadan sadece “açık/kapalı” bilgisiyle ilerlemek

Bu alternatifler yönetilebilirlik ve şeffaflık sorunları nedeniyle tercih edilmemiştir.

---

# 4. Gerekçeler (Rationale)

- Standart adlandırma, flag’lerin domain ve amaç bazında kolay gruplanmasını sağlar.
- Yaşam döngüsü tanımı, stale flag birikimini kontrol altında tutar.
- Telemetry ile flag etkisini ölçmek, rollout kararlarını veriyle destekler.

---

# 5. Sonuçlar (Consequences)

- Flag yönetimi için bakım süreci gerekiyor; bayrak sayısı kontrol altında tutulmalı.
- Telemetry pipeline’ında flag metadata’sını taşıyacak ek alanlar eklenecek.
- Unleash ile CI/CD entegrasyonu (örn. pipeline’dan toggle) için script geliştirmek gerekiyor.

### Acceptance / Metrics

- Her flag için yaşam döngüsü kaydı mevcut; kapatılmayan (stale) flag sayısı minimal (<10, takip).
- Kill-switch flag 1 dakika içinde etkinleşiyor ve canary guardrail tetiklenince otomatik tutuyor.
- Flag etkisi telemetry dashboardlarında izlenebilir (kullanım/etki grafikleri).

---

# 6. Uygulama Detayları (Implementation Notes)

- Unleash ortam konfigürasyonları ve naming kuralı dokümante edilmelidir.
- Backstage veya benzer platformda flag listesi görünürlüğü sağlanmalıdır.

---

# 7. Durum Geçmişi (Status History)

| Tarih       | Durum    | Not                                   |
|-------------|----------|----------------------------------------|
| 2025-11-02  | Accepted | İlk sürüm, ADR-009 kararı kabul edildi |

---

# 8. Notlar

- Flag borcunu yönetmek için periyodik temizlik (cleanup) süreci planlanmalıdır.

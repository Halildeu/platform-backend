# ADR-011 – Observability Korelasyonu

**Durum (Status):** Accepted  
**Tarih:** 2025-11-02  

**İlgili Dokümanlar (Traceability):**
- SPEC: `docs/05-governance/06-specs/SPEC-E06-S01-TELEMETRY-OBSERVABILITY-V1.md`
- ACCEPTANCE: `docs/05-governance/07-acceptance/E06-S01-Telemetry-and-Observability.acceptance.md`
- STORY: `docs/05-governance/02-stories/E06-S01-Telemetry-and-Observability.md`
- İlgili ADR’ler: ADR-004, ADR-006
- STYLE GUIDE: (Varsa STYLE-OBS-001)

---

# 1. Bağlam (Context)

- Telemetry taksonomisi (ADR-004) TTFA ve hata oranlarını ölçmeyi hedefliyor; backend tarafıyla korelasyon henüz yok.
- FE/BE zincirini izlemek için W3C Trace Context (`traceparent`) ve correlation-id standardı gerekiyor.

---

# 2. Karar (Decision)

- Shell tüm dış isteklerde W3C Trace Context başlıklarını (`traceparent`, `tracestate`) işleyip backend’e iletecek; backend bunu OpenTelemetry ile yayacak.
- Shell’de üretilen telemetry event’leri aynı trace-id/correlation-id ile geçilecek; Grafana Tempo + Loki panoları FE↔BE takibini gösterecek.
- TTFA, hata oranı, fallback oranı için SLO’lar ve alarm eşikleri belirlenecek (ör. TTFA soğuk p95 ≤ 8 sn). Guardrail aşımı alert oluşturacak.

---

# 3. Alternatifler (Alternatives)

- Sadece correlation-id kullanmak, trace context’i standardize etmemek
- Sadece backend tarafında tracing yapmak, FE olaylarını bağlamamak

Bu alternatifler uçtan uca izlenebilirlik sağlamadığı için tercih edilmemiştir.

---

# 4. Gerekçeler (Rationale)

- W3C Trace Context ve OpenTelemetry, standart ve araç-ekosistemi güçlü çözümlerdir.
- FE/BE zincirini aynı trace-id ile bağlamak, problem çözme ve optimizasyon süreçlerini hızlandırır.

---

# 5. Sonuçlar (Consequences)

- Backend ekiplerinin de OTEL entegrasyonlarını trace context’e uyarlaması gerekiyor.
- Telemetry event şemasına trace-id, flag-id gibi ek alanlar eklenecek; log hacmi artabilir.
- SLO panoları (Grafana) konfigüre edilmeli; alert kanalları (Opsgenie/Slack) belirlenmeli.

### Acceptance / Metrics

- FE→BE trace zinciri Tempo’da eksiksiz görüntüleniyor (örnekleme oranı tanımlı). 
- Alarm eşikleri devrede; guardrail aşımında otomatik uyarı geliyor.
- SLO raporlaması (TTFA, hata, fallback) flow/demo sunumlarında paylaşılıyor.

---

# 6. Uygulama Detayları (Implementation Notes)

- Shell ve backend servisleri için OTEL SDK’ları ve exporter konfigürasyonları standardize edilmelidir.

---

# 7. Durum Geçmişi (Status History)

| Tarih       | Durum    | Not                                   |
|-------------|----------|----------------------------------------|
| 2025-11-02  | Accepted | İlk sürüm, ADR-011 kararı kabul edildi |

---

# 8. Notlar

- Trace örnekleme oranı ve veri saklama süreleri gelecekte ayrı ADR ile güncellenebilir.

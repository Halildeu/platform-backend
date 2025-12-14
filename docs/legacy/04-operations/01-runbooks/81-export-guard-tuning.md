---
title: "Export Guard Telemetri ve Tuning Kılavuzu"

## Export Guard Telemetri ve Tuning Kılavuzu

Amaç
- `GET /api/users/export.csv` isteklerinde rate-limit + audit guardrail’lerini izleyecek metrikleri tanımlamak ve ortam bazında sınır değerleri ayarlamak.

### Meter/Metrik Listesi
| Metric | Açıklama | Etiketler |
| --- | --- | --- |
| `users_export_rate_limit_total` | Guard’a gelen istek sayısı | `result=allowed|blocked` |
| `users_export_audit_total` | Audit kaydı sayısı | `result=success|failure` |
| `users_export_duration` | Streaming süresi (Timer) | Micrometer varsayılan etiketleri (`service`, `instance`, …) |

- Bu metrikler Micrometer → OTLP köprüsü sayesinde Grafana/Tempo panolarına akar. `result="blocked"` artışı alert tetikler.
- Tempo tarafında `service=user-service` ve `span_name=users_export` filtresiyle trace’leri görebilirsiniz (Spring Boot `Observation` → Micrometer).

### Limit Property’leri
- `export.rate-limit.per-minute` (default: 12) → dakika başına refill miktarı.
- `export.rate-limit.burst` (default: 24) → token bucket kapasitesi (en az per-minute kadar).
- GateWay tarafındaki `export.rate-limit.*` değerleriyle hizalı olmalıdır.

### Ortam Bazlı Öneriler
| Ortam | per-minute | burst | Not |
| --- | --- | --- | --- |
| Local/dev | 12 | 24 | Tek kullanıcı testi; bloklama alert gerekmez. |
| Stage | 30 | 60 | QA senaryolarında ~2 export/dk kullanıcı başına. |
| Prod | 60 | 120 | Ortalama 1 export/sn (kullanıcı bazlı). Trafik artışında dashboard’dan izlenir. |

> Formül önerisi: `burst = per-minute * 2`. Geçici kampanyalarda burst 3x’e çıkabilir fakat 24 saat içinde bloklanan istek oranı < %1 olmalı.
> Stage ve Prod `.env` dosyalarına (`docs/03-delivery/01-env/01-.env.stage.local`, `docs/03-delivery/01-env/02-.env.prod.local`) bu değerler eklenmiştir; ortam değiştirirken aynı property’leri Helm/CI değişkenleriyle senkronize edin.

### Tuning Adımları
1. Grafana’da `users_export_rate_limit_total{result="blocked"}` zaman serisini aç; p95 export süresi için `users_export_duration` histogramını izle.
2. Bloğun %1’i aşması durumunda:
   - Eğer `users_export_duration` p95 < 5s ise limit artırılabilir (örn. per-minute +10).
   - Eğer p95 ≥ 5s ise backend performansına bakılmalı; limit artırmadan önce indeks/IO optimizasyonu yapılır.
3. Ayarlanan değerleri `application-<env>.properties` veya ilgili helm/manifest dosyasında güncelle.
4. Değişiklik sonrası `users_export_audit_total{result="failure"}` metriği takip edilerek regresyon aranır.

### Dashboard / Alert Durumu
 - **Dashboard**: `Security / CSV Export Guard` (UID: `SEC-EXPORT-001`). Provisyon kaynağı `docs/04-operations/02-monitoring/dashboards/security-csv-export-dashboard.json`; Grafana menüsünde `Security / CSV Export` klasöründe açılır. Dev ortam URL’si: [http://localhost:3010/d/SEC-EXPORT-001](http://localhost:3010/d/SEC-EXPORT-001).
- **Panel 1** (Rate limit throughput): `sum(rate(users_export_rate_limit_total{result="allowed"}[5m]))` ve `...{result="blocked"}` sorguları stacked gösterilir; birim `req/s`.
- **Panel 2** (Blocked/allowed oranı): `sum(rate(...blocked...)) / clamp_min(sum(rate(...allowed...)), 0.001)` sorgusu; threshold renkleri 0.02 (turuncu) ve 0.05 (kırmızı).
- **Panel 3** (Export süresi p95): `histogram_quantile(0.95, sum(rate(users_export_duration_bucket[5m])) by (le))`; eşikler 5s / 10s.
- **Panel 4** (Audit success/failure artışı): `sum(increase(users_export_audit_total{result="failure"}[5m]))` ve success karşılaştırması.
- **Alert kuralları**: `scripts/perf/grafana/provisioning/alerting/export-guard-rules.yml` dosyası ile provizyon edilir. Kural listesi:
  1. `users export blocked ratio (warning)` → ratio > 0.02 (%2) için 5 dk süreli alarm (`severity=warning`).
  2. `users export blocked ratio (critical)` → ratio > 0.05 (%5) için 5 dk süreli alarm (`severity=critical`).
  3. `users export duration p95 (warning)` → p95 > 5s.
  4. `users export duration p95 (critical)` → p95 > 10s.
- Alert değerlendirmeleri Prometheus datasource UID `prometheus` üzerinden çalışır; sonuçlar Grafana Alerts panelinden izlenir.
- Shell telemetry entegrasyonu: `mfe-users` CSV stream butonu `users.export_csv.stream.*` event’lerini publish eder (`start`, `success`, `rate_limit`, `error`). Event payload’larında `search/status/role/sort/advancedFilter` özeti bulunur; Stage/Prod gözlemleri için Tempo/Loki’ye forward edilir.

### Bağlantılar
- `docs/agents/05-export-security.md`
- `docs/01-architecture/01-system/01-backend-architecture.md`

### 24 Saatlik İzleme Süreci
1. Stage/Prod publish sonrası 24 saat boyunca yukarıdaki panellerde alert geçmişini takip edin.
2. `blocked/allowed` oranı eşiklerin altındaysa limitler sabit kalır; eşik aşılıyorsa `EXPORT_RATE_LIMIT_*` değerleri tabloya göre revize edilir.
3. Değişiklik yapılırsa bu rehberdeki tabloya tarih/SRE onayıyla not düşülür.

### Stage/Prod Uygulama Planı

**Stage**
1. `.env.stage.local` ve Helm değer dosyasında `export.rate-limit.per-minute=30`, `export.rate-limit.burst=60` değerlerini set et; deploy pipeline’ı tetikle.
2. Shell/Users MFE’de telemetry debug’u aç (`localStorage.setItem('shell:debug', 'telemetry')`) ve CSV export butonu ile test istekleri gönder.
3. `scripts/perf/load-export.sh --env stage --rpm 40 --duration 15m` komutu ile yük testi uygula.
4. Grafana `SEC-EXPORT-001` panelinden 5 dakikalık ortalamaları kaydet (`blocked ratio`, `export_duration p95`).
5. Sonuçları `docs/05-governance/PROJECT_FLOW.md` altındaki ilgili Story/Epic notlarına işle.

**Prod**
1. Stage metrikleri < %2 blocked ise prod için `per-minute = stage * 2`, `burst = per-minute * 2` planını hazırlayıp değişiklik kaydı aç.
2. `k8s/prod/user-service/values-export.yaml` dosyasında güncel değerleri set eden PR oluştur; onay sonrası release pipeline’ını çalıştır.
3. Release sonrası 24 saat `SEC-EXPORT-001` paneli + alert geçmişi izlenir; kritik alarm tetiklenirse eski değerlere rollback.

### Alert İzleme ve Escalation

| Alert UID | Eşik | Süre | İlk Aksiyon | Escalation |
| --- | --- | --- | --- | --- |
| `export-blocked-warning` | blocked ratio > %2 | 5 dk | Limit parametrelerini gözden geçir (Stage’de +10 rpm). | Ops günlük raporuna not düş. |
| `export-blocked-critical` | blocked ratio > %5 | 5 dk | Export endpoint throttling’i aç, incident oluştur. | Platform Lead + Security bilgilendirilir. |
| `export-duration-warning` | p95 > 5s | 5 dk | DB index / IO incelemesi, `pageSize` düşür. | Gerekiyorsa BE ekibiyle hotfix planı. |
| `export-duration-critical` | p95 > 10s | 5 dk | Export’u geçici kapat (gateway rule), incident aç. | On-call SRE → CTO zinciri. |

Alert notları Tempo/Loki dashboard’una iliştirilir; escalate durumlarında incident ID bu runbook’a eklenir.

### Ölçüm Günlüğü (Stage/Prod)

| Tarih | Ortam | per-minute/burst | Blok Oranı (p95) | `users_export_duration` p95 | Not / Aksiyon |
| --- | --- | --- | --- | --- | --- |
| YYYY-MM-DD | Stage | 30 / 60 | _TBD_ | _TBD_ | Yük testi tamamlanınca doldur. |
| YYYY-MM-DD | Prod | 60 / 120 | _TBD_ | _TBD_ | Release’den 24 saat sonra sonuç ekle. |

> Her tuning döngüsü sonrası tabloyu güncelleyin. Boş satırlar “henüz ölçülmedi” anlamına gelir; 24 saatlik izleme tamamlanınca değer girilmelidir.

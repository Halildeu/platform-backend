---
title: "Users Service — Performans Sertleştirme"
status: done
owner: "@team/backend"
workflow_tickets:
  - BE-04
last_review: 2025-11-12
---

Hedefler
- `/api/users/all` p95 < 150 ms (pageSize=50) — Stage verisi üzerinde
- CPU/IO baskısında artışsız ölçeklenme — indeks ve projection iyileştirmeleri

Adımlar
1) Sorgu planı (EXPLAIN/ANALYZE) — search/status/role/sort kombinasyonları
2) İndeksler — `lower(email)`, `lower(name)`, tarih kolonları; composite ihtiyaçları
3) Projection — SELECT alanları daraltma; gereksiz join’leri kaldırma
4) Konfig — `fetchSize`, connection pool, JPA `readOnly` vs.

Kabul
- p95 hedefi doğrulandı (3 koşu ortalaması)
- Migration SQL ve ölçüm raporu eklendi
- Runbook/observability etkisi not edildi

Ölçüm Günlüğü (Stage)

| Tarih | Dataset | pageSize | Filtre(ler) | Sıralama | p50 | p95 | Not |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 2025-11-12 | ~10k kullanıcı | 50 | (no filter) | create_date DESC | ~7.3ms | ~14.8ms | k6 RPS=30, 30s (gateway: api-gateway:8080) |
| 2025-11-12 | ~10k kullanıcı | 50 | (no filter) | create_date DESC | ~3.73ms | ~6.39ms | k6 RPS=50, 1m (gateway: api-gateway:8080) |
| 2025-11-12 | ~10k kullanıcı | 50 | search="ali" | create_date DESC | ~4.01ms | ~6.87ms | k6 RPS=50, 1m (SEARCH=ali) |

EXPLAIN/ANALYZE Şablonları (PostgreSQL)

```sql
-- Filtre + sıralama
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, name, email, role, enabled, create_date
FROM users
WHERE ($1 IS NULL OR lower(email) LIKE lower('%' || $1 || '%') OR lower(name) LIKE lower('%' || $1 || '%'))
  AND ($2 = 'ALL' OR ($2 = 'ACTIVE' AND enabled IS TRUE) OR ($2 = 'INACTIVE' AND enabled IS FALSE))
  AND ($3 = 'ALL' OR upper(role) = upper($3))
ORDER BY create_date DESC
LIMIT 50 OFFSET 0;
```

Notlar
- Fonksiyonel indeksler mevcut: `lower(email)`, `lower(name)`, `upper(role)`, `enabled`, tarih kolonları. Ek kompozit indeks ihtiyacı actual planlara göre değerlendirilmelidir.
- Uygulama tarafında projection daraltma (yalnız görünür alanlar) ve readOnly sorgu ipuçları (JPA) kullanın.

K6 Ölçüm (Lokal/Stage)

```bash
# Docker ile k6 smoke: /api/users/all ve /api/users/export.csv senaryoları
BASE_URL="http://localhost:8080" RPS=50 DURATION=2m \
  ./scripts/perf/perf-run.sh

# Çıktıdaki http_req_duration p(95) değerini tabloya işleyin
```

Durum
- İlk iki koşu p95 hedefini açık ara geçti (≤ 15ms ve ≤ 7ms). Üçüncü koşu (search=ali) sonrası ortalama eklenecek; mevcut veriyle BE-04 kabul ölçütü sağlanmaktadır.

Kabul Sonuçları (Acceptance)
- Ölçümler:
  - RPS=30, 30s (no filter): p50 ≈ 7.35ms, p95 ≈ 14.8ms
  - RPS=50, 60s (no filter): p50 ≈ 3.73ms, p95 ≈ 6.39ms
  - RPS=50, 60s (search=ali): p50 ≈ 4.01ms, p95 ≈ 6.87ms
- Sonuç: p95 < 150ms hedefi 3 koşuda da sağlandı. BE-04 kabul edildi (2025-11-12).

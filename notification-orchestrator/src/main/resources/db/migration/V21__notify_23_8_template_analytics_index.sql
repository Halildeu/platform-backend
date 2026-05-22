-- Faz 23.8 M7 T4.3.7 PR-2 — per-template analytics supporting index
-- (Codex thread `019e4ee2` plan-time AGREE + `019e4fb0` post-impl iter-1
-- REVISE absorb — RECOMMEND_PG_FULL_BREAKDOWN_NO_TEMPLATE_PROM_LABEL).
-- Gitops PR-1 #966 MERGED (Grafana PG datasource + Top 20 Templates panel).
--
-- BAĞLAM
-- ------
-- T4.3.7 per-template analytics, Grafana PostgreSQL datasource panel "Top 20
-- Templates by Send Volume + Success Rate" ile sağlanır (gitops PR-1 #966 —
-- per-tenant-dashboard.yaml panel id=8). Panel query'si:
--
--   SELECT COALESCE(ae.template_id, ae.details->>'template_id', ae.topic_key)
--            AS template_key,
--          COUNT(DISTINCT ae.delivery_id) AS total_count, ...
--   FROM notify.audit_event_v2 ae
--   LEFT JOIN notify.notification_delivery nd ON nd.id = ae.delivery_id
--   WHERE ae.org_id = ${tenant}
--     AND ae.occurred_at BETWEEN ${from} AND ${to}
--     AND COALESCE(ae.template_id, ae.details->>'template_id', ae.topic_key)
--           IS NOT NULL
--   GROUP BY template_key
--   ORDER BY total_count DESC
--   LIMIT 20;
--
-- INDEX TASARIMI — (org_id, occurred_at)
-- -------------------------------------
-- Mevcut V8 audit_event_v2 index'leri sorgu erişim yolunu karşılamıyor:
--   - idx_audit_event_v2_correlation_id   (correlation_id, occurred_at DESC)
--   - idx_audit_event_v2_recipient_hash   (recipient_hash, occurred_at DESC)
--   - idx_audit_event_v2_intent_id        (intent_id, occurred_at DESC)
--   - idx_audit_event_v2_event_type       (event_type, occurred_at DESC)
-- Hiçbiri `org_id` lider kolonlu değil. Panel query `org_id` eşitlik +
-- `occurred_at` aralık filtreler; bu index ikisini tek erişim yolunda
-- karşılar:
--   - `org_id` lider eşitlik kolonu (tenant scope; panel her zaman tek tenant)
--   - `occurred_at` ikinci kolon aralık taraması ($from..$to window)
--
-- Codex 019e4fb0 iter-1 P2 absorb: `template_id` ÜÇÜNCÜ B-tree key OLARAK
-- EKLENMEDİ. Gerekçe:
--   * B-tree erişiminde `org_id` equality + `occurred_at` range sonrası 3.
--     key global grouping order SAĞLAMAZ — `GROUP BY template_key` yine
--     post-scan aggregate (Sort/HashAggregate) ile yapılır.
--   * Panel query index-only OLAMAZ: `delivery_id`, `details`, `topic_key`
--     index'te değil → her satır heap fetch gerektirir.
--   * `template_id` 3. key sadece index write/storage bloat getirirdi,
--     seek/group faydası yok.
-- Sonuç: minimal erişim yolu `(org_id, occurred_at)`. GROUP BY/COALESCE
-- fallback maliyeti canlı EXPLAIN ANALYZE ile ölçülür; gerekirse ayrı
-- follow-up'ta expression index veya covering INCLUDE tasarımı değerlendirilir
-- (bu PR scope dışı).
--
-- PARTITION DAVRANIŞI
-- ------------------
-- audit_event_v2 `PARTITION BY RANGE (occurred_at)` (V8). PostgreSQL 11+
-- partitioned-parent üzerinde CREATE INDEX mevcut tüm partition'lara local
-- index oluşturur + yeni partition'lara otomatik clone eder. Ayrı
-- per-partition DDL gerekmez.
--
-- CONCURRENTLY KULLANILMADI
-- ------------------------
-- Plain `CREATE INDEX` (CONCURRENTLY değil): pre-prod tablo küçük + Flyway
-- her migration'ı transaction içinde sarar (CREATE INDEX CONCURRENTLY
-- transaction içinde çalışamaz). V16 (idx_inbox_subscriber_history) aynı
-- precedent'i izledi.
--
-- Codex 019e4fb0 iter-1 P3 absorb: canlı-büyük tablo escape path'i
-- partitioned parent için **tek seferde parent-level CONCURRENTLY DEĞİL**.
-- Postgres partitioned parent üzerinde `CREATE INDEX CONCURRENTLY`
-- desteklenmez. Doğru büyük-tablo modeli:
--   1. Her partition için ayrı ayrı `CREATE INDEX CONCURRENTLY` (lock-light)
--   2. Parent'ta `CREATE INDEX ... ONLY` (invalid placeholder) +
--      `ALTER INDEX ... ATTACH PARTITION` ile per-partition index'leri bağla
-- Bu model ayrı migration + `-- flyway:executeInTransaction=false` script
-- config gerektirir; canlı tablo yazma yükü altında büyürse uygulanır.
--
-- CARDINALITY NOTU
-- ----------------
-- Bu migration **0 yeni Prometheus serisi** ekler. T4.3.7 Codex 019e4ee2
-- kararı: template_id Prometheus Counter label YASAK (channel×status×org_id×
-- template_id ≈ 4.8M peak series → Prometheus cardinality budget ihlali).
-- Per-template analytics PG aggregate read-time query ile sağlanır; bu index
-- o query'nin p95 ≤ 2s (24h window) / ≤ 5s (7d window) bütçesini destekler.
--
-- ACCEPTANCE (operator — post-merge live verify)
-- ----------------------------------------------
-- EXPLAIN ANALYZE ile p95 ölçümü canlı veride yapılır (operator):
--   EXPLAIN (ANALYZE, BUFFERS)
--   SELECT COALESCE(ae.template_id, ae.details->>'template_id', ae.topic_key)
--            AS template_key, COUNT(DISTINCT ae.delivery_id) ...
--   FROM notify.audit_event_v2 ae
--   LEFT JOIN notify.notification_delivery nd ON nd.id = ae.delivery_id
--   WHERE ae.org_id = '<tenant>'
--     AND ae.occurred_at BETWEEN NOW() - INTERVAL '24 hours' AND NOW()
--     AND COALESCE(ae.template_id, ae.details->>'template_id', ae.topic_key)
--           IS NOT NULL
--   GROUP BY template_key ORDER BY total_count DESC LIMIT 20;
-- Beklenen: partition pruning + Index/Bitmap Scan using
-- idx_audit_event_v2_org_occurred (org_id eq + occurred_at range);
-- GROUP BY post-scan HashAggregate; p95 24h ≤ 2s.

CREATE INDEX idx_audit_event_v2_org_occurred
    ON notify.audit_event_v2 (org_id, occurred_at);

COMMENT ON INDEX notify.idx_audit_event_v2_org_occurred IS
    'Faz 23.8 M7 T4.3.7 PR-2 — serves per-template analytics Grafana panel '
    'access path: (org_id) tenant equality + (occurred_at) time-window range '
    'scan. GROUP BY template_key is a post-scan aggregate (no B-tree grouping '
    'support). PG aggregate read path — no template_id Prometheus label '
    '(Codex 019e4ee2 cardinality decision). Partitioned parent index '
    'auto-propagates to all audit_event_v2 partitions (PG 11+).';

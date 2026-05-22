-- Faz 23.8 M7 T4.3.7 PR-2 — per-template analytics supporting index
-- (Codex thread `019e4ee2` plan-time AGREE — RECOMMEND_PG_FULL_BREAKDOWN_
-- NO_TEMPLATE_PROM_LABEL; gitops PR-1 #966 MERGED).
--
-- BAĞLAM
-- ------
-- T4.3.7 per-template analytics, Grafana PostgreSQL datasource panel "Top 20
-- Templates by Send Volume + Success Rate" ile sağlanır (gitops PR-1 #966 —
-- kustomize/base/monitoring/grafana-dashboards/notification-orchestrator-
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
-- INDEX TASARIMI
-- --------------
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
--   - `template_id` üçüncü kolon — GROUP BY template_key'in dominant terimi
--     (COALESCE fallback details->>'template_id' / topic_key index'lenmez ama
--     bunlar nadir; ana yük template_id non-null satırlar)
--
-- PARTITION DAVRANIŞI
-- ------------------
-- audit_event_v2 `PARTITION BY RANGE (occurred_at)` (V8). PostgreSQL 11+
-- partitioned-parent üzerinde CREATE INDEX tüm mevcut + gelecek partition'lara
-- otomatik propagate eder (her partition kendi local index'ini alır). Ayrı
-- per-partition DDL gerekmez.
--
-- CONCURRENTLY KULLANILMADI
-- ------------------------
-- Plain `CREATE INDEX` (CONCURRENTLY değil): pre-prod tablo küçük + Flyway her
-- migration'ı transaction içinde sarar (CREATE INDEX CONCURRENTLY transaction
-- içinde çalışamaz). V16 (idx_inbox_subscriber_history) aynı precedent'i
-- izledi. Canlı tablo yazma yükü altında büyürse CONCURRENTLY'ye revize
-- edilir (ayrı migration; `-- flyway:executeInTransaction=false` script config).
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
-- Beklenen: Index Scan / Bitmap Index Scan using
-- idx_audit_event_v2_org_occurred_template; p95 24h ≤ 2s.

CREATE INDEX idx_audit_event_v2_org_occurred_template
    ON notify.audit_event_v2 (org_id, occurred_at, template_id);

COMMENT ON INDEX notify.idx_audit_event_v2_org_occurred_template IS
    'Faz 23.8 M7 T4.3.7 PR-2 — serves per-template analytics Grafana panel: '
    '(org_id) tenant equality + (occurred_at) time-window range scan + '
    '(template_id) GROUP BY support. PG aggregate read path (no template_id '
    'Prometheus label — Codex 019e4ee2 cardinality decision). Partitioned '
    'parent index auto-propagates to all audit_event_v2 partitions (PG 11+).';

-- BE-024c v2-c-pre-2-C-A source-pair ordering tuple (Codex 019e89a3 iter-3 AGREE).
--
-- v2-c-pre-2-B shipped the dormant AFTER_COMMIT cache refresh listener +
-- REQUIRES_NEW refresh service infrastructure. v2-c-pre-2-C-A activates
-- it by adding the writer-side source-order guard that closes the
-- residual stale-overwrite window the caller contract alone cannot
-- protect under overlapping listener tx.
--
-- The guard compares the incoming source-pair's
-- (captured_at | collected_at, created_at, id) tuple with the stored
-- cache row's tuple via a direct EXCLUDED.source_* >= c.source_*
-- comparison — no nested correlated subquery (which proved hard to make
-- work in PG's ON CONFLICT WHERE during the v2-B first attempt).
--
-- Three new NOT NULL columns per cache table:
--   * source_captured_at — software: state_history.captured_at;
--                          outdated: outdated_snapshots.collected_at
--   * source_created_at  — same source table's created_at
--   * source_row_id      — same source table's id (the to_*_id value)
--
-- Pre-V28 rows backfill:
--   * Software OK / NO_CHANGE / INSUFFICIENT_HISTORY rows have a non-null
--     to_history_id by V27 status shape invariant — JOIN with
--     endpoint_software_inventory_state_history populates the tuple from
--     the source row's actual (captured_at, created_at, id).
--   * Outdated mirror with endpoint_outdated_software_snapshots.
--   * NO_HISTORY rows carry no source pair (to_*_id IS NULL); fill with
--     epoch/zero-UUID sentinel so the guard treats them as "always older
--     than any incoming source-pair".
--
-- Fail-loud: if any row is still NULL after the join + sentinel backfill,
-- the migration RAISES. A silent NULL would let the writer's NOT NULL
-- ALTER fail later with a less informative error.

-- Software cache: add columns nullable.
ALTER TABLE endpoint_software_diff_cache
    ADD COLUMN source_captured_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN source_created_at  TIMESTAMP WITH TIME ZONE,
    ADD COLUMN source_row_id      UUID;

-- Outdated cache: add columns nullable.
ALTER TABLE endpoint_outdated_software_diff_cache
    ADD COLUMN source_captured_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN source_created_at  TIMESTAMP WITH TIME ZONE,
    ADD COLUMN source_row_id      UUID;

-- Software: join-backfill from state-history source-of-truth.
UPDATE endpoint_software_diff_cache c
SET source_captured_at = h.captured_at,
    source_created_at  = h.created_at,
    source_row_id      = h.id
FROM endpoint_software_inventory_state_history h
WHERE c.to_history_id = h.id
  AND c.tenant_id     = h.tenant_id
  AND c.device_id     = h.device_id;

-- Software: NO_HISTORY rows -> epoch/sentinel tuple.
UPDATE endpoint_software_diff_cache c
SET source_captured_at = TIMESTAMPTZ '1970-01-01 00:00:00+00',
    source_created_at  = TIMESTAMPTZ '1970-01-01 00:00:00+00',
    source_row_id      = '00000000-0000-0000-0000-000000000000'::uuid
WHERE c.status = 'NO_HISTORY'
  AND c.source_captured_at IS NULL;

-- Outdated: join-backfill from snapshot source-of-truth.
UPDATE endpoint_outdated_software_diff_cache c
SET source_captured_at = s.collected_at,
    source_created_at  = s.created_at,
    source_row_id      = s.id
FROM endpoint_outdated_software_snapshots s
WHERE c.to_snapshot_id = s.id
  AND c.tenant_id      = s.tenant_id
  AND c.device_id      = s.device_id;

-- Outdated: NO_HISTORY rows -> epoch/sentinel tuple.
UPDATE endpoint_outdated_software_diff_cache c
SET source_captured_at = TIMESTAMPTZ '1970-01-01 00:00:00+00',
    source_created_at  = TIMESTAMPTZ '1970-01-01 00:00:00+00',
    source_row_id      = '00000000-0000-0000-0000-000000000000'::uuid
WHERE c.status = 'NO_HISTORY'
  AND c.source_captured_at IS NULL;

-- Fail-loud (Codex 019e89a3 iter-2 absorb): any row missing any of the
-- three tuple components after backfill stops the migration. A non-
-- NO_HISTORY row whose source row was retention-swept (so the JOIN
-- finds no match) would otherwise be promoted to NOT NULL with stale
-- nulls, or worse, sneak past with one of the three components set.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM endpoint_software_diff_cache
        WHERE source_captured_at IS NULL
           OR source_created_at  IS NULL
           OR source_row_id      IS NULL
    ) THEN
        RAISE EXCEPTION 'V28 migration failed: endpoint_software_diff_cache rows without source order tuple after backfill';
    END IF;
    IF EXISTS (
        SELECT 1 FROM endpoint_outdated_software_diff_cache
        WHERE source_captured_at IS NULL
           OR source_created_at  IS NULL
           OR source_row_id      IS NULL
    ) THEN
        RAISE EXCEPTION 'V28 migration failed: endpoint_outdated_software_diff_cache rows without source order tuple after backfill';
    END IF;
END $$;

-- Promote to NOT NULL — writer guard depends on the tuple being present.
ALTER TABLE endpoint_software_diff_cache
    ALTER COLUMN source_captured_at SET NOT NULL,
    ALTER COLUMN source_created_at  SET NOT NULL,
    ALTER COLUMN source_row_id      SET NOT NULL;

ALTER TABLE endpoint_outdated_software_diff_cache
    ALTER COLUMN source_captured_at SET NOT NULL,
    ALTER COLUMN source_created_at  SET NOT NULL,
    ALTER COLUMN source_row_id      SET NOT NULL;

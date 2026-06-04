-- V35 — Faz 21.1 Cleanup C2a — diff-cache org-key IDENTITY (not FK flip)
--       (Codex 019e919e REVISE → AGREE for THIS bounded C2a scope)
--
-- BOUNDARY: this migration flips the diff-cache UPSERT IDENTITY from
-- tenant-keyed to org-keyed (UNIQUE conflict target), and adds the cache
-- non-null org_id evidence the org identity requires. It DOES drop the old
-- tenant-keyed UNIQUE via an atomic swap (Phase 2 — required so a single
-- (org_id, device_id) arbiter exists for ON CONFLICT; see header below). It
-- intentionally does NOT recreate the cache FOREIGN KEYS as org-composite
-- (deferred to C2b) and does NOT touch the tenant_id column.
--
-- WHY the cache FK flip is DEFERRED to C2b (Codex 019e919e REVISE):
--   A cache FK (child_col, org_id) -> parent(id, org_id) depends on the
--   PARENT source tables (endpoint_devices,
--   endpoint_software_inventory_state_history,
--   endpoint_outdated_software_snapshots) having org_id NOT NULL. C1/V34
--   added only UNIQUE(id, org_id) on those parents; it deliberately did
--   NOT enforce parent org_id NOT NULL (source reads still support legacy
--   NULL via the effective-org OR-fallback, defended by the *EffectiveOrg
--   test suite). Recreating the 6 cache FKs to org-composite NOW would
--   silently require the parent invariant flip C1 deferred — the FK form
--   of the C1 non-null-CHECK coupling. So the 6 tenant-composite cache FKs
--   STAY here; the FK flip ships with the source non-null / OR-fallback
--   retirement family (C2b + C1.5 #444).
--
-- WHY the cache non-null CHECK IS safe here (unlike the source tables):
--   Cache reads have NO legacy-NULL OR-fallback contract — the V33 cache
--   test never constructs an org_id-NULL cache row, and DiffCacheService
--   writes org_id canonically (= tenantId) on every UPSERT. Org-keyed
--   identity REQUIRES non-null org_id: with nullable org_id, UNIQUE treats
--   multiple (NULL, device_id) as distinct and a composite FK MATCH SIMPLE
--   bypasses. So C2a machine-enforces cache org_id non-null via CHECK.
--   (Column-level SET NOT NULL still deferred to C4.)
--
-- WHY the old UNIQUE(tenant_id, device_id) is DROPPED, not kept
-- (Codex 019e919e final — corrected by a deterministic concurrency test):
--   Two redundant unique constraints on the same logical key (org_id =
--   tenant_id) are INCOMPATIBLE with concurrent ON CONFLICT. PostgreSQL
--   ON CONFLICT handles only ONE arbiter index; a racing speculative
--   insertion can conflict on the NON-arbiter unique, which is then an
--   unhandled unique_violation (DiffCacheServiceConcurrencyPostgresIntegration
--   Test fails deterministically with both uniques present). The symmetry
--   means there is no safe "keep both" intermediate — org-keyed identity
--   REQUIRES dropping the tenant-keyed unique so (org_id, device_id) is the
--   sole arbiter. The swap is safe because Phase 1 machine-enforces cache
--   org_id non-null AND V33 enforces org_id = tenant_id → (org_id, device_id)
--   is logically equivalent to the dropped (tenant_id, device_id).
--
-- ROLLBACK BOUNDARY (the C2a coupling): once V35 commits, an image older
--   than V35 issuing ON CONFLICT (tenant_id, device_id) is NO LONGER
--   rollback-compatible (old unique gone → runtime "no unique or exclusion
--   constraint matching"). Rollback target MUST be >= a V35-aware digest
--   (standard Flyway forward-only boundary, charter F21-R29). Old-writer-pod
--   overlap is NOT supported: V35 is a schema/app atomic boundary. Pre-prod/
--   testai: restart/recreate/scale discipline. Prod: runbook gate.
--
-- LOCK BUDGET: plain transactional ALTER (CHECK NOT VALID is instant;
--   VALIDATE + ADD UNIQUE scan tiny tables). endpoint-admin not yet in
--   prod; Flyway runs at bootstrap before traffic; testai caches = 6 rows
--   each. CONCURRENTLY escalation path == V34 header.
--
-- LIVE EVIDENCE (testai, read-only, immediately before authoring):
--   both cache tables: org_id NULL = 0, tenant_id<>org_id = 0,
--   dup(org_id, device_id) = 0 → preflight passes, VALIDATE passes,
--   UNIQUE(org_id, device_id) add succeeds.
--
-- References:
--   - V33 (cache org_id compat: ADD COLUMN + backfill + trigger + V30 CHECK)
--   - V34 (C1 parent UNIQUE(id, org_id))
--   - Codex thread 019e919e (C2 REVISE → C2a bounded; C2b FK flip deferred)
--   - platform-k8s-gitops docs/faz-21/cleanup-execution-plan.md (C2 phase)

-- ============================================================
-- Phase 0: preflight fail-loud. The org-key identity is only sound if the
-- cache is already canonical. RAISE if any cache row violates the
-- preconditions, so a drifted deploy target aborts before the flip.
-- ============================================================

DO $$
DECLARE
    bad BIGINT;
    cache_tables TEXT[] := ARRAY[
        'endpoint_software_diff_cache',
        'endpoint_outdated_software_diff_cache'
    ];
    t TEXT;
BEGIN
    FOREACH t IN ARRAY cache_tables LOOP
        EXECUTE format('SELECT count(*) FROM %I WHERE org_id IS NULL', t) INTO bad;
        IF bad > 0 THEN
            RAISE EXCEPTION 'V35 precondition failed: % has % org_id NULL rows', t, bad;
        END IF;
        EXECUTE format('SELECT count(*) FROM %I WHERE tenant_id IS NOT NULL AND org_id IS NOT NULL AND tenant_id <> org_id', t) INTO bad;
        IF bad > 0 THEN
            RAISE EXCEPTION 'V35 precondition failed: % has % tenant_id<>org_id rows', t, bad;
        END IF;
        EXECUTE format('SELECT count(*) FROM (SELECT 1 FROM %I WHERE org_id IS NOT NULL GROUP BY org_id, device_id HAVING count(*) > 1) d', t) INTO bad;
        IF bad > 0 THEN
            RAISE EXCEPTION 'V35 precondition failed: % has % duplicate (org_id, device_id) groups', t, bad;
        END IF;
    END LOOP;
END $$;

-- ============================================================
-- Phase 1: cache org_id non-null evidence gate (CHECK NOT VALID + VALIDATE).
-- Required for org-keyed identity (UNIQUE + ON CONFLICT). Column stays
-- nullable; the VALIDATED CHECK is the enforcement surface until C4.
-- ============================================================

ALTER TABLE endpoint_software_diff_cache
    ADD CONSTRAINT swdc_org_id_not_null CHECK (org_id IS NOT NULL) NOT VALID;
ALTER TABLE endpoint_software_diff_cache
    VALIDATE CONSTRAINT swdc_org_id_not_null;

ALTER TABLE endpoint_outdated_software_diff_cache
    ADD CONSTRAINT osdc_org_id_not_null CHECK (org_id IS NOT NULL) NOT VALID;
ALTER TABLE endpoint_outdated_software_diff_cache
    VALIDATE CONSTRAINT osdc_org_id_not_null;

-- ============================================================
-- Phase 2: org-keyed UNIQUE identity — ATOMIC SWAP (add new, drop old).
-- The new UNIQUE(org_id, device_id) becomes the SOLE ON CONFLICT arbiter
-- for the DiffCacheService UPSERTs; the old UNIQUE(tenant_id, device_id) is
-- dropped (single arbiter required for concurrent ON CONFLICT — see header).
-- Add-new-before-drop-old: a failed new-unique build leaves the old unique
-- intact on rollback. DDL is transactional; the transient dual-unique state
-- is never a committed runtime state — after COMMIT only the org unique exists.
-- ============================================================

ALTER TABLE endpoint_software_diff_cache
    ADD CONSTRAINT swdc_org_id_device_id_key UNIQUE (org_id, device_id);
ALTER TABLE endpoint_software_diff_cache
    DROP CONSTRAINT swdc_tenant_device_uq;

ALTER TABLE endpoint_outdated_software_diff_cache
    ADD CONSTRAINT osdc_org_id_device_id_key UNIQUE (org_id, device_id);
ALTER TABLE endpoint_outdated_software_diff_cache
    DROP CONSTRAINT osdc_tenant_device_uq;

-- V34 — Faz 21.1 Cleanup C1 Source Org-Key Foundation
--       (Codex 019e919e plan-time PARTIAL → ready_for_impl for THIS bounded scope)
--
-- BOUNDARY (Codex 019e919e verbatim):
--   This migration only establishes non-null org_id evidence and
--   org-composite FK target eligibility for later cache flips. It
--   intentionally does NOT rewrite tenant-scoped FKs, remove effective-org
--   fallback reads, or commit to the final tenant_id drop strategy because
--   live FK dependency closure extends beyond the 9 org_id-bearing tables.
--
-- This migration does exactly two additive, non-breaking things:
--
--   (A) Non-null evidence gate: CHECK (org_id IS NOT NULL) NOT VALID +
--       VALIDATE CONSTRAINT on all 9 org_id-bearing endpoint tables.
--       V30 already enforces CHECK (org_id IS NULL OR org_id = tenant_id);
--       combined with (A) the row-level invariant becomes the canonical
--       org_id = tenant_id (non-null AND equal). VALIDATE fails loud
--       (SQLSTATE 23514) if any live row still has org_id NULL — this is
--       the machine-enforced precondition for a future direct-org_id read
--       path (the effective-org OR-fallback removal, deferred to a separate
--       code PR with prod-shaped evidence). Final `SET NOT NULL` is
--       deferred to C4 (cheaper + provable once this CHECK is VALIDATED).
--
--   (B) FK-target enabler: ADD CONSTRAINT UNIQUE (id, org_id) on the 3
--       cache parents (endpoint_devices,
--       endpoint_software_inventory_state_history,
--       endpoint_outdated_software_snapshots). A composite FK
--       (child_col, org_id) -> parent (id, org_id) REQUIRES a UNIQUE/PK on
--       exactly parent (id, org_id); PK(id) alone does NOT satisfy it.
--       C2 (cache org-key flip) recreates the cache FKs against this
--       target, so the UNIQUE must land first (charter F21-R31
--       parent-before-child ordering).
--
-- WHY THE SCOPE IS BOUNDED (live FK-web discovery, 2026-06-04, testai):
--   Only these 9 tables carry org_id. endpoint_devices is referenced by
--   14 inbound composite FKs (child_col, tenant_id) -> devices(id,
--   tenant_id), ~10 of whose children have NO org_id (device-rooted
--   snapshot tree: health, diagnostics, hardware, hotfix, services,
--   startup_exposure...). endpoint_install_audit itself FKs to
--   endpoint_commands + endpoint_software_catalog_items, which have NO
--   org_id. So the dependency closure of "drop endpoint_devices.tenant_id"
--   is the whole device-rooted tree, NOT 9 tables. The final FK-web /
--   C4 drop strategy is therefore REOPENED (tracked in
--   platform-k8s-gitops docs/faz-21/cleanup-execution-plan.md +
--   risk F21-R32). This migration commits to none of it.
--
-- LOCK BUDGET (Codex 019e919e Q4 — "açık lock-budget yazılmalı"):
--   (B) uses a plain transactional `ALTER TABLE ADD CONSTRAINT UNIQUE`
--   (table scan under ACCESS EXCLUSIVE). This is acceptable here because:
--     - endpoint-admin-service is NOT yet in production (Faz 22.5 P2-A
--       D30 cutover BLOCKED; prod cluster/DB never bootstrapped). Flyway
--       applies pending migrations at startup BEFORE traffic, so V34 runs
--       during the FIRST prod bootstrap on an empty/tiny endpoint_devices
--       — zero write-stall.
--     - testai data is sub-100 rows (devices=6).
--     - The constraint is unfailable: id is the PRIMARY KEY, so
--       (id, org_id) is trivially unique for ANY org_id values; the scan
--       cannot raise a duplicate.
--   ESCALATION (durable note): if this migration is ever applied
--   incrementally to an ALREADY-LARGE endpoint_devices (e.g. a second
--   pre-populated cluster), replace (B) with the online pattern:
--     CREATE UNIQUE INDEX CONCURRENTLY ux_..._id_org_id ON <t>(id, org_id);
--     ALTER TABLE <t> ADD CONSTRAINT <t>_id_org_id_key UNIQUE USING INDEX ux_...;
--   (requires a non-transactional Flyway script: `executeInTransaction=false`
--   sidecar, since CONCURRENTLY cannot run inside a transaction).
--   Keeping V34 transactional gives all-or-nothing rollback safety, which
--   for sub-100-row tables strictly dominates the CONCURRENTLY complexity.
--
-- LIVE EVIDENCE captured immediately before authoring (testai, read-only):
--   All 9 tables: org_id NULL count = 0 AND tenant_id<>org_id mismatch = 0
--   → the VALIDATE step in (A) passes on testai. Prod-shaped re-proof is
--   the operator-bound C3 gate; this migration's VALIDATE is itself the
--   fail-loud machine check at each deploy target.
--
-- Anti-pattern guards:
--   - No DROP COLUMN tenant_id (C4 scope).
--   - No FK rewrite (deferred; final strategy unresolved).
--   - No repository read-path change (separate code PR).
--   - org_id stays NULLABLE at the column level; the VALIDATED CHECK is
--     the enforcement surface until C4 SET NOT NULL.
--   - Flyway forward-only; app-side rollback = redeploy prior digest
--     (both columns still written; reads still use effective-org fallback).
--
-- References:
--   - V29 (compat layer + trigger), V30 (org_id=tenant_id CHECK pattern),
--     V33 (cache org_id compat) — same 9-table set.
--   - platform-k8s-gitops docs/faz-21/cleanup-execution-plan.md (C1 phase)
--   - Codex thread 019e919e (this bounded scope PARTIAL → impl)
--   - Codex thread 019e8f95 (C0 plan parent-before-child ordering)

-- ============================================================
-- (A) Non-null evidence gate — Phase 1: ADD CONSTRAINT NOT VALID
--     (cheap; enforces new + updated rows immediately, no scan)
-- ============================================================

ALTER TABLE endpoint_devices
    ADD CONSTRAINT endpoint_devices_org_id_not_null
    CHECK (org_id IS NOT NULL) NOT VALID;

ALTER TABLE endpoint_software_inventory_state_history
    ADD CONSTRAINT endpoint_sw_inv_state_org_id_not_null
    CHECK (org_id IS NOT NULL) NOT VALID;

ALTER TABLE endpoint_outdated_software_snapshots
    ADD CONSTRAINT endpoint_outdated_sw_snap_org_id_not_null
    CHECK (org_id IS NOT NULL) NOT VALID;

ALTER TABLE endpoint_outdated_software_packages
    ADD CONSTRAINT endpoint_outdated_sw_pkg_org_id_not_null
    CHECK (org_id IS NOT NULL) NOT VALID;

ALTER TABLE endpoint_install_audit
    ADD CONSTRAINT endpoint_install_audit_org_id_not_null
    CHECK (org_id IS NOT NULL) NOT VALID;

ALTER TABLE endpoint_compliance_evaluations
    ADD CONSTRAINT endpoint_compliance_eval_org_id_not_null
    CHECK (org_id IS NOT NULL) NOT VALID;

ALTER TABLE endpoint_app_control_snapshots
    ADD CONSTRAINT endpoint_app_control_snap_org_id_not_null
    CHECK (org_id IS NOT NULL) NOT VALID;

ALTER TABLE endpoint_software_diff_cache
    ADD CONSTRAINT swdc_org_id_not_null
    CHECK (org_id IS NOT NULL) NOT VALID;

ALTER TABLE endpoint_outdated_software_diff_cache
    ADD CONSTRAINT osdc_org_id_not_null
    CHECK (org_id IS NOT NULL) NOT VALID;

-- ============================================================
-- (A) Non-null evidence gate — Phase 2: VALIDATE CONSTRAINT
--     (full-table scan; fails loud SQLSTATE 23514 if any live row has
--      org_id NULL — the machine-enforced precondition)
-- ============================================================

ALTER TABLE endpoint_devices VALIDATE CONSTRAINT endpoint_devices_org_id_not_null;
ALTER TABLE endpoint_software_inventory_state_history VALIDATE CONSTRAINT endpoint_sw_inv_state_org_id_not_null;
ALTER TABLE endpoint_outdated_software_snapshots VALIDATE CONSTRAINT endpoint_outdated_sw_snap_org_id_not_null;
ALTER TABLE endpoint_outdated_software_packages VALIDATE CONSTRAINT endpoint_outdated_sw_pkg_org_id_not_null;
ALTER TABLE endpoint_install_audit VALIDATE CONSTRAINT endpoint_install_audit_org_id_not_null;
ALTER TABLE endpoint_compliance_evaluations VALIDATE CONSTRAINT endpoint_compliance_eval_org_id_not_null;
ALTER TABLE endpoint_app_control_snapshots VALIDATE CONSTRAINT endpoint_app_control_snap_org_id_not_null;
ALTER TABLE endpoint_software_diff_cache VALIDATE CONSTRAINT swdc_org_id_not_null;
ALTER TABLE endpoint_outdated_software_diff_cache VALIDATE CONSTRAINT osdc_org_id_not_null;

-- ============================================================
-- (B) FK-target enabler — UNIQUE (id, org_id) on the 3 cache parents.
--     Enables C2 composite cache FK (child_col, org_id) -> parent(id, org_id).
--     Additive: coexists with existing PRIMARY KEY (id) + UNIQUE (id, tenant_id).
--     See LOCK BUDGET above for the transactional-vs-CONCURRENTLY rationale.
-- ============================================================

ALTER TABLE endpoint_devices
    ADD CONSTRAINT endpoint_devices_id_org_id_key UNIQUE (id, org_id);

ALTER TABLE endpoint_software_inventory_state_history
    ADD CONSTRAINT endpoint_sw_inv_state_id_org_id_key UNIQUE (id, org_id);

ALTER TABLE endpoint_outdated_software_snapshots
    ADD CONSTRAINT endpoint_outdated_sw_snap_id_org_id_key UNIQUE (id, org_id);

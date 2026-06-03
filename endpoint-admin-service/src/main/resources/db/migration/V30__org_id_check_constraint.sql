-- V30 — Faz 21.1 PR2a org_id CHECK constraint (Codex 019e8ca1 plan-time AGREE Option B)
--
-- Adds `CHECK (org_id IS NULL OR org_id = tenant_id)` on each of the 7
-- tenant-scoped endpoint tables introduced by V29. Pattern:
--   1) ADD CONSTRAINT ... NOT VALID — applies to new + updated rows
--      immediately; skips full-table validation scan (cheap)
--   2) VALIDATE CONSTRAINT — full-table validation in a separate ALTER
--      statement
--
-- IMPORTANT (Codex 019e8ca1 iter-1 F3 absorb): the NOT VALID + VALIDATE
-- separation is a SQL-level idiom (two ALTER statements). Both run in
-- this single Flyway migration (one transaction/deploy unit). The
-- semantic value is observable separation in the migration script (each
-- step explicit; rollback narrows to what failed); it is NOT a multi-
-- migration operator checkpoint. If a true operator checkpoint is needed
-- (e.g. for huge tables where VALIDATE scan must be scheduled), split
-- Phase 1 into V30 and Phase 2 into V31.
--
-- Precondition (PR1 V29 guarantee): mismatch=0 across all 7 tables at the
-- time V29 ran. V30 VALIDATE step re-enforces this on the live snapshot
-- before the constraint is marked valid.
--
-- Operator pre-merge evidence (Codex 019e8ca1 iter-1 F6):
--   Run mismatch=0 probe on live prod data IMMEDIATELY before V30 deploys.
--   V30 intentionally fails (RAISE in VALIDATE step) if PR1's documented
--   drift case occurred between V29 and V30 deploys. The audit script
--   docs/scripts/faz-21/audit-and-check.sh (PR-3 A in sister repo
--   platform-k8s-gitops) can produce this evidence with multi-DB.
--
-- Behavior after V30 lands:
--   - Legacy writer (tenant_id only): BEFORE INSERT/UPDATE trigger (V29)
--     fills org_id = tenant_id → CHECK passes.
--   - Canonical writer (both columns equal): CHECK passes.
--   - Bug case (both columns supplied, different values): CHECK fails
--     with SQLSTATE 23514. This was the "documented drift" case in V29
--     PR1 (Codex iter-1 P1); PR2a binding upgrade.
--
-- Anti-pattern guards (Codex 019e8ca1 + PR-5 chain):
--   - NOT VALID + VALIDATE pattern keeps the live-row validation step
--     observable separately from the constraint addition step (rollback
--     surface narrows if VALIDATE fails)
--   - Trigger from V29 stays; CHECK enforces invariant at the row level
--   - NO DROP COLUMN tenant_id (cleanup PR scope, after window evidence)
--   - PR2b (code-side dual-read COALESCE + canonical write) is the next
--     binding PR; this PR only adds the DB invariant
--
-- References:
--   - V29 (PR #391 MERGED 2026-06-03): compat layer + trigger + initial
--     mismatch=0 backfill
--   - charter §4.1 Inv-2 (tenant-scoped persistence MUST hold)
--   - ADR-0032 §3.2 (org_id canonical column)
--   - docs/faz-23-evidence/2026-06-03-faz-21-dryrun-on-test-cluster.md §3

-- Phase 1: ADD CONSTRAINT NOT VALID (cheap; new + updated rows enforce)

ALTER TABLE endpoint_devices
    ADD CONSTRAINT endpoint_devices_org_id_tenant_id_match
    CHECK (org_id IS NULL OR org_id = tenant_id) NOT VALID;

ALTER TABLE endpoint_software_inventory_state_history
    ADD CONSTRAINT endpoint_sw_inv_state_org_id_match
    CHECK (org_id IS NULL OR org_id = tenant_id) NOT VALID;

ALTER TABLE endpoint_outdated_software_snapshots
    ADD CONSTRAINT endpoint_outdated_sw_snap_org_id_match
    CHECK (org_id IS NULL OR org_id = tenant_id) NOT VALID;

ALTER TABLE endpoint_outdated_software_packages
    ADD CONSTRAINT endpoint_outdated_sw_pkg_org_id_match
    CHECK (org_id IS NULL OR org_id = tenant_id) NOT VALID;

ALTER TABLE endpoint_install_audit
    ADD CONSTRAINT endpoint_install_audit_org_id_match
    CHECK (org_id IS NULL OR org_id = tenant_id) NOT VALID;

ALTER TABLE endpoint_compliance_evaluations
    ADD CONSTRAINT endpoint_compliance_eval_org_id_match
    CHECK (org_id IS NULL OR org_id = tenant_id) NOT VALID;

ALTER TABLE endpoint_app_control_snapshots
    ADD CONSTRAINT endpoint_app_control_snap_org_id_match
    CHECK (org_id IS NULL OR org_id = tenant_id) NOT VALID;

-- Phase 2: VALIDATE CONSTRAINT (full-table scan; ensures live data complies)

ALTER TABLE endpoint_devices VALIDATE CONSTRAINT endpoint_devices_org_id_tenant_id_match;
ALTER TABLE endpoint_software_inventory_state_history VALIDATE CONSTRAINT endpoint_sw_inv_state_org_id_match;
ALTER TABLE endpoint_outdated_software_snapshots VALIDATE CONSTRAINT endpoint_outdated_sw_snap_org_id_match;
ALTER TABLE endpoint_outdated_software_packages VALIDATE CONSTRAINT endpoint_outdated_sw_pkg_org_id_match;
ALTER TABLE endpoint_install_audit VALIDATE CONSTRAINT endpoint_install_audit_org_id_match;
ALTER TABLE endpoint_compliance_evaluations VALIDATE CONSTRAINT endpoint_compliance_eval_org_id_match;
ALTER TABLE endpoint_app_control_snapshots VALIDATE CONSTRAINT endpoint_app_control_snap_org_id_match;

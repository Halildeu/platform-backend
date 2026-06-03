-- V29 — Faz 21.1 endpoint org_id compat layer (Codex 019e8c95 plan-time AGREE)
--
-- Adds `org_id` UUID column alongside existing `tenant_id` column on 7
-- tenant-scoped endpoint tables. Backfills `org_id = tenant_id` for all
-- existing rows. Creates indexes on the new column. Adds BEFORE INSERT
-- + BEFORE UPDATE triggers so legacy writers (services still emitting
-- only `tenant_id`) automatically populate `org_id` from `tenant_id` at
-- write time.
--
-- This migration is the **compat layer** that lets the rolling deploy
-- proceed without breaking in-flight services. Code-side dual-read
-- COALESCE(org_id, tenant_id) + canonical org_id write happens in PR2.
-- Drop of `tenant_id` and `NOT NULL` constraint on `org_id` happens in
-- a later cleanup PR after at least one deploy/rollback window passes
-- with mismatch=0 evidence.
--
-- Pre-flight evidence requirements (per Codex 019e8c95):
--   - row count parity (before/after) per table
--   - tenant_id != org_id mismatch count = 0 immediately post-migration
--   - distinct tenant count preserved
--   - old-writer (tenant_id only) INSERT/UPDATE → trigger fills org_id
--   - new-writer (org_id explicit) INSERT/UPDATE → trigger leaves it
--
-- Anti-pattern guards:
--   - org_id stays NULLABLE until PR2 evidence (legacy writers + dual-
--     write code path proven)
--   - trigger is purely additive (does NOT modify when both values set
--     and differ — see test V29BackfillAndTriggerPostgresIntegrationTest)
--   - no DROP COLUMN tenant_id here (cleanup PR scope)
--   - Flyway forward-only; rollback path is app-side (revert deploy +
--     legacy code still reads tenant_id)
--
-- Tables migrated (charter §1.1 live state drift list from PR-5 dry-run
-- evidence 2026-06-03):
--   1. endpoint_devices
--   2. endpoint_software_inventory_state_history
--   3. endpoint_outdated_software_snapshots
--   4. endpoint_outdated_software_packages
--   5. endpoint_install_audit
--   6. endpoint_compliance_evaluations
--   7. endpoint_app_control_snapshots
--
-- References:
--   - charter §1.1 + ADR-0032 §3.2 (live state drift)
--   - docs/faz-23-evidence/2026-06-03-faz-21-dryrun-on-test-cluster.md §3

-- ============================================================
-- Trigger function: BEFORE INSERT/UPDATE — fill org_id from tenant_id
-- when caller leaves it null. Idempotent + side-effect-free for
-- callers that already supply org_id.
-- ============================================================

CREATE OR REPLACE FUNCTION endpoint_org_id_compat_fill()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.org_id IS NULL AND NEW.tenant_id IS NOT NULL THEN
        NEW.org_id := NEW.tenant_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION endpoint_org_id_compat_fill() IS
    'Faz 21.1 endpoint org_id compat layer (V29). Fills org_id from tenant_id when caller leaves it null. Safe to remove after cleanup PR drops tenant_id.';

-- ============================================================
-- Per-table: ADD org_id + backfill + index + trigger
-- ============================================================

-- Table 1: endpoint_devices
ALTER TABLE endpoint_devices ADD COLUMN IF NOT EXISTS org_id UUID;
UPDATE endpoint_devices SET org_id = tenant_id WHERE org_id IS NULL AND tenant_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_endpoint_devices_org_id ON endpoint_devices(org_id);
DROP TRIGGER IF EXISTS endpoint_devices_org_id_compat ON endpoint_devices;
CREATE TRIGGER endpoint_devices_org_id_compat
    BEFORE INSERT OR UPDATE ON endpoint_devices
    FOR EACH ROW EXECUTE FUNCTION endpoint_org_id_compat_fill();

-- Table 2: endpoint_software_inventory_state_history
ALTER TABLE endpoint_software_inventory_state_history ADD COLUMN IF NOT EXISTS org_id UUID;
UPDATE endpoint_software_inventory_state_history SET org_id = tenant_id WHERE org_id IS NULL AND tenant_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_endpoint_sw_inv_state_org_id ON endpoint_software_inventory_state_history(org_id);
DROP TRIGGER IF EXISTS endpoint_sw_inv_state_org_id_compat ON endpoint_software_inventory_state_history;
CREATE TRIGGER endpoint_sw_inv_state_org_id_compat
    BEFORE INSERT OR UPDATE ON endpoint_software_inventory_state_history
    FOR EACH ROW EXECUTE FUNCTION endpoint_org_id_compat_fill();

-- Table 3: endpoint_outdated_software_snapshots
ALTER TABLE endpoint_outdated_software_snapshots ADD COLUMN IF NOT EXISTS org_id UUID;
UPDATE endpoint_outdated_software_snapshots SET org_id = tenant_id WHERE org_id IS NULL AND tenant_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_endpoint_outdated_sw_snap_org_id ON endpoint_outdated_software_snapshots(org_id);
DROP TRIGGER IF EXISTS endpoint_outdated_sw_snap_org_id_compat ON endpoint_outdated_software_snapshots;
CREATE TRIGGER endpoint_outdated_sw_snap_org_id_compat
    BEFORE INSERT OR UPDATE ON endpoint_outdated_software_snapshots
    FOR EACH ROW EXECUTE FUNCTION endpoint_org_id_compat_fill();

-- Table 4: endpoint_outdated_software_packages
ALTER TABLE endpoint_outdated_software_packages ADD COLUMN IF NOT EXISTS org_id UUID;
UPDATE endpoint_outdated_software_packages SET org_id = tenant_id WHERE org_id IS NULL AND tenant_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_endpoint_outdated_sw_pkg_org_id ON endpoint_outdated_software_packages(org_id);
DROP TRIGGER IF EXISTS endpoint_outdated_sw_pkg_org_id_compat ON endpoint_outdated_software_packages;
CREATE TRIGGER endpoint_outdated_sw_pkg_org_id_compat
    BEFORE INSERT OR UPDATE ON endpoint_outdated_software_packages
    FOR EACH ROW EXECUTE FUNCTION endpoint_org_id_compat_fill();

-- Table 5: endpoint_install_audit
ALTER TABLE endpoint_install_audit ADD COLUMN IF NOT EXISTS org_id UUID;
UPDATE endpoint_install_audit SET org_id = tenant_id WHERE org_id IS NULL AND tenant_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_endpoint_install_audit_org_id ON endpoint_install_audit(org_id);
DROP TRIGGER IF EXISTS endpoint_install_audit_org_id_compat ON endpoint_install_audit;
CREATE TRIGGER endpoint_install_audit_org_id_compat
    BEFORE INSERT OR UPDATE ON endpoint_install_audit
    FOR EACH ROW EXECUTE FUNCTION endpoint_org_id_compat_fill();

-- Table 6: endpoint_compliance_evaluations
ALTER TABLE endpoint_compliance_evaluations ADD COLUMN IF NOT EXISTS org_id UUID;
UPDATE endpoint_compliance_evaluations SET org_id = tenant_id WHERE org_id IS NULL AND tenant_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_endpoint_compliance_eval_org_id ON endpoint_compliance_evaluations(org_id);
DROP TRIGGER IF EXISTS endpoint_compliance_eval_org_id_compat ON endpoint_compliance_evaluations;
CREATE TRIGGER endpoint_compliance_eval_org_id_compat
    BEFORE INSERT OR UPDATE ON endpoint_compliance_evaluations
    FOR EACH ROW EXECUTE FUNCTION endpoint_org_id_compat_fill();

-- Table 7: endpoint_app_control_snapshots
ALTER TABLE endpoint_app_control_snapshots ADD COLUMN IF NOT EXISTS org_id UUID;
UPDATE endpoint_app_control_snapshots SET org_id = tenant_id WHERE org_id IS NULL AND tenant_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_endpoint_app_control_snap_org_id ON endpoint_app_control_snapshots(org_id);
DROP TRIGGER IF EXISTS endpoint_app_control_snap_org_id_compat ON endpoint_app_control_snapshots;
CREATE TRIGGER endpoint_app_control_snap_org_id_compat
    BEFORE INSERT OR UPDATE ON endpoint_app_control_snapshots
    FOR EACH ROW EXECUTE FUNCTION endpoint_org_id_compat_fill();

-- ============================================================
-- Post-migration sanity (fail-loud if mismatch > 0 after backfill).
-- Codex 019e8c95: "tenant_id != org_id mismatch count = 0 immediately
-- post-migration". This DO block raises if backfill is incomplete.
-- ============================================================

DO $$
DECLARE
    mismatch_count BIGINT;
    table_name TEXT;
    target_tables TEXT[] := ARRAY[
        'endpoint_devices',
        'endpoint_software_inventory_state_history',
        'endpoint_outdated_software_snapshots',
        'endpoint_outdated_software_packages',
        'endpoint_install_audit',
        'endpoint_compliance_evaluations',
        'endpoint_app_control_snapshots'
    ];
BEGIN
    FOREACH table_name IN ARRAY target_tables LOOP
        EXECUTE format('SELECT count(*) FROM %I WHERE tenant_id IS NOT NULL AND (org_id IS NULL OR org_id != tenant_id)', table_name)
            INTO mismatch_count;
        IF mismatch_count > 0 THEN
            RAISE EXCEPTION 'V29 backfill mismatch on table %: % rows have tenant_id != org_id (or org_id NULL)', table_name, mismatch_count;
        END IF;
    END LOOP;
END $$;

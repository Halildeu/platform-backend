-- V42 — Faz 21.1 Cleanup C4 A2 slice-5 — startup_exposure org expansion
--       (mirrors V40/V41 2-detail leaf-family pattern)
--
-- BOUNDARY: org-expands the startup_exposure family (root
-- endpoint_startup_exposure_snapshots + details endpoint_startup_exposure_apps
-- + endpoint_startup_exposure_probe_errors) and flips its 3 tenant-composite
-- FKs to org-composite. Fifth (last simple) leaf family. No legacy-NULL
-- coupling → full vertical in one migration. No tenant_id drop (A6);
-- entities/repository/grid JOIN unchanged (reads tenant-keyed = org_id; A5).
-- Single-column source_command_result_id FK out of scope.
--
-- WHY SOUND: identical to V38-V41 — child org_id NON-NULL + device_id/
-- snapshot_id NOT NULL; parents UNIQUE(id, org_id) (devices V34 + snapshots
-- here) + org_id NOT NULL (devices V36 LIVE) → MATCH SIMPLE rejects 23503.
--
-- References: V25 (startup_exposure create), V29 (compat trigger fn),
--   V34/V36 (devices, LIVE), V40/V41 (leaf pattern), manifest §6.

-- Phase 1: org_id + backfill + compat trigger + index (3 tables).
ALTER TABLE endpoint_startup_exposure_snapshots ADD COLUMN IF NOT EXISTS org_id UUID;
UPDATE endpoint_startup_exposure_snapshots SET org_id = tenant_id WHERE org_id IS NULL AND tenant_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_endpoint_se_snap_org_id ON endpoint_startup_exposure_snapshots(org_id);
DROP TRIGGER IF EXISTS endpoint_se_snap_org_id_compat ON endpoint_startup_exposure_snapshots;
CREATE TRIGGER endpoint_se_snap_org_id_compat BEFORE INSERT OR UPDATE ON endpoint_startup_exposure_snapshots
    FOR EACH ROW EXECUTE FUNCTION endpoint_org_id_compat_fill();

ALTER TABLE endpoint_startup_exposure_apps ADD COLUMN IF NOT EXISTS org_id UUID;
UPDATE endpoint_startup_exposure_apps SET org_id = tenant_id WHERE org_id IS NULL AND tenant_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_endpoint_se_app_org_id ON endpoint_startup_exposure_apps(org_id);
DROP TRIGGER IF EXISTS endpoint_se_app_org_id_compat ON endpoint_startup_exposure_apps;
CREATE TRIGGER endpoint_se_app_org_id_compat BEFORE INSERT OR UPDATE ON endpoint_startup_exposure_apps
    FOR EACH ROW EXECUTE FUNCTION endpoint_org_id_compat_fill();

ALTER TABLE endpoint_startup_exposure_probe_errors ADD COLUMN IF NOT EXISTS org_id UUID;
UPDATE endpoint_startup_exposure_probe_errors SET org_id = tenant_id WHERE org_id IS NULL AND tenant_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_endpoint_se_pe_org_id ON endpoint_startup_exposure_probe_errors(org_id);
DROP TRIGGER IF EXISTS endpoint_se_pe_org_id_compat ON endpoint_startup_exposure_probe_errors;
CREATE TRIGGER endpoint_se_pe_org_id_compat BEFORE INSERT OR UPDATE ON endpoint_startup_exposure_probe_errors
    FOR EACH ROW EXECUTE FUNCTION endpoint_org_id_compat_fill();

-- Phase 2: match + non-null CHECK (3 tables).
ALTER TABLE endpoint_startup_exposure_snapshots ADD CONSTRAINT endpoint_se_snap_org_id_match CHECK (org_id IS NULL OR org_id = tenant_id) NOT VALID;
ALTER TABLE endpoint_startup_exposure_snapshots VALIDATE CONSTRAINT endpoint_se_snap_org_id_match;
ALTER TABLE endpoint_startup_exposure_snapshots ADD CONSTRAINT endpoint_se_snap_org_id_not_null CHECK (org_id IS NOT NULL) NOT VALID;
ALTER TABLE endpoint_startup_exposure_snapshots VALIDATE CONSTRAINT endpoint_se_snap_org_id_not_null;

ALTER TABLE endpoint_startup_exposure_apps ADD CONSTRAINT endpoint_se_app_org_id_match CHECK (org_id IS NULL OR org_id = tenant_id) NOT VALID;
ALTER TABLE endpoint_startup_exposure_apps VALIDATE CONSTRAINT endpoint_se_app_org_id_match;
ALTER TABLE endpoint_startup_exposure_apps ADD CONSTRAINT endpoint_se_app_org_id_not_null CHECK (org_id IS NOT NULL) NOT VALID;
ALTER TABLE endpoint_startup_exposure_apps VALIDATE CONSTRAINT endpoint_se_app_org_id_not_null;

ALTER TABLE endpoint_startup_exposure_probe_errors ADD CONSTRAINT endpoint_se_pe_org_id_match CHECK (org_id IS NULL OR org_id = tenant_id) NOT VALID;
ALTER TABLE endpoint_startup_exposure_probe_errors VALIDATE CONSTRAINT endpoint_se_pe_org_id_match;
ALTER TABLE endpoint_startup_exposure_probe_errors ADD CONSTRAINT endpoint_se_pe_org_id_not_null CHECK (org_id IS NOT NULL) NOT VALID;
ALTER TABLE endpoint_startup_exposure_probe_errors VALIDATE CONSTRAINT endpoint_se_pe_org_id_not_null;

-- Phase 3: snapshots UNIQUE(id, org_id).
ALTER TABLE endpoint_startup_exposure_snapshots ADD CONSTRAINT endpoint_se_snap_id_org_id_key UNIQUE (id, org_id);

-- Phase 4: preflight fail-loud.
DO $$
DECLARE bad BIGINT;
BEGIN
    SELECT count(*) INTO bad FROM endpoint_startup_exposure_snapshots s
        WHERE NOT EXISTS (SELECT 1 FROM endpoint_devices d WHERE d.id = s.device_id AND d.org_id = s.org_id);
    IF bad > 0 THEN RAISE EXCEPTION 'V42 preflight: % se_snapshots rows have no endpoint_devices(id, org_id) parent', bad; END IF;
    SELECT count(*) INTO bad FROM endpoint_startup_exposure_apps a
        WHERE NOT EXISTS (SELECT 1 FROM endpoint_startup_exposure_snapshots s WHERE s.id = a.snapshot_id AND s.org_id = a.org_id);
    IF bad > 0 THEN RAISE EXCEPTION 'V42 preflight: % se_apps rows have no snapshots(id, org_id) parent', bad; END IF;
    SELECT count(*) INTO bad FROM endpoint_startup_exposure_probe_errors p
        WHERE NOT EXISTS (SELECT 1 FROM endpoint_startup_exposure_snapshots s WHERE s.id = p.snapshot_id AND s.org_id = p.org_id);
    IF bad > 0 THEN RAISE EXCEPTION 'V42 preflight: % se_probe_errors rows have no snapshots(id, org_id) parent', bad; END IF;
    SELECT count(*) INTO bad FROM endpoint_startup_exposure_apps a JOIN endpoint_startup_exposure_snapshots s ON s.id = a.snapshot_id WHERE a.org_id <> s.org_id;
    IF bad > 0 THEN RAISE EXCEPTION 'V42 preflight: % se_apps rows whose org_id <> parent snapshot org_id', bad; END IF;
    SELECT count(*) INTO bad FROM endpoint_startup_exposure_probe_errors p JOIN endpoint_startup_exposure_snapshots s ON s.id = p.snapshot_id WHERE p.org_id <> s.org_id;
    IF bad > 0 THEN RAISE EXCEPTION 'V42 preflight: % se_probe_errors rows whose org_id <> parent snapshot org_id', bad; END IF;
END $$;

-- Phase 5: FK flips — atomic add-NOT VALID + VALIDATE + drop-old, CASCADE preserved.
ALTER TABLE endpoint_startup_exposure_apps
    ADD CONSTRAINT se_app_snapshot_org_fk FOREIGN KEY (snapshot_id, org_id)
        REFERENCES endpoint_startup_exposure_snapshots (id, org_id) ON DELETE CASCADE NOT VALID;
ALTER TABLE endpoint_startup_exposure_apps VALIDATE CONSTRAINT se_app_snapshot_org_fk;
ALTER TABLE endpoint_startup_exposure_apps DROP CONSTRAINT se_app_snapshot_fk;

ALTER TABLE endpoint_startup_exposure_probe_errors
    ADD CONSTRAINT se_pe_snapshot_org_fk FOREIGN KEY (snapshot_id, org_id)
        REFERENCES endpoint_startup_exposure_snapshots (id, org_id) ON DELETE CASCADE NOT VALID;
ALTER TABLE endpoint_startup_exposure_probe_errors VALIDATE CONSTRAINT se_pe_snapshot_org_fk;
ALTER TABLE endpoint_startup_exposure_probe_errors DROP CONSTRAINT se_pe_snapshot_fk;

ALTER TABLE endpoint_startup_exposure_snapshots
    ADD CONSTRAINT se_snap_device_org_fk FOREIGN KEY (device_id, org_id)
        REFERENCES endpoint_devices (id, org_id) ON DELETE CASCADE NOT VALID;
ALTER TABLE endpoint_startup_exposure_snapshots VALIDATE CONSTRAINT se_snap_device_org_fk;
ALTER TABLE endpoint_startup_exposure_snapshots DROP CONSTRAINT se_snap_device_fk;

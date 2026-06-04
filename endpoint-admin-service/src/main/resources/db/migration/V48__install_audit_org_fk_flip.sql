-- V48 — Faz 21.1 Cleanup C4 step-8 — endpoint_install_audit 3 FK flips
--       (PURE-FLIP variant; the first hub-dependent consumer flip)
--
-- BOUNDARY: flips endpoint_install_audit's 3 tenant-composite FKs to
-- org-composite. install_audit is already ORG-DONE (org_id column + backfill +
-- compat trigger from V29 table 5, non-null CHECK convalidated from V36), and
-- now all 3 FK parents carry UNIQUE(id, org_id): endpoint_devices (V34),
-- endpoint_commands (V46 hub), endpoint_software_catalog_items (V47 hub). So NO
-- org_id machinery and NO new UNIQUE are needed here — this is a pure FK flip,
-- the install_audit analogue of V45. No tenant_id drop (A6); reads tenant-keyed
-- (A5). This is the FIRST of the hub-dependent consumer flips; it could only
-- land after BOTH hub foundations (V46 commands + V47 catalog) established their
-- (id, org_id) targets.
--
-- The 3 flips (manifest §2 install_audit edges; ON DELETE preserved exactly):
--   install_audit.(device_id, org_id)   -> endpoint_devices(id, org_id)   CASCADE
--   install_audit.(command_id, org_id)  -> endpoint_commands(id, org_id)   CASCADE
--   install_audit.(catalog_item_id, org_id) -> catalog(id, org_id)         RESTRICT
-- The catalog FK keeps ON DELETE RESTRICT (a catalog item with install history
-- must not be silently deletable — the BE-021 audit-integrity invariant); the
-- device + command FKs keep CASCADE.
--
-- WHY SOUND: install_audit org_id NON-NULL (V36 CHECK convalidated) +
-- device_id/command_id/catalog_item_id NOT NULL; each parent UNIQUE(id, org_id)
-- + org_id NOT NULL. MATCH SIMPLE composite FK then rejects cross-org rows
-- (23503). Every row is canonical (org_id = tenant_id on both child and
-- parents — V30/V36 match CHECKs LIVE), so the flip is lossless; P4 preflight is
-- fail-loud on any residual drift before each VALIDATE.
--
-- LOCK BUDGET: ADD ... NOT VALID takes SHARE ROW EXCLUSIVE briefly; VALIDATE
-- scans without blocking writes; DROP is catalog-only. endpoint-admin-service is
-- NOT yet in production; Flyway runs at bootstrap before traffic; testai
-- install_audit = 15 rows. No CONCURRENTLY needed at this scale.
--
-- LIVE EVIDENCE (testai, read-only, immediately before authoring): install_audit
--   org_id column present; org_id NULL = 0 (15 rows);
--   endpoint_install_audit_org_id_not_null CHECK convalidated; the 3 FKs are
--   still (child_col, tenant_id) -> parent(id, tenant_id) [_device CASCADE,
--   _command CASCADE, _catalog RESTRICT]; devices/commands/catalog all carry
--   UNIQUE(id, org_id). => preflight passes, all 3 VALIDATE pass.
--
-- References: V12 (install_audit create + tenant-composite FKs), V29 (org_id
--   compat + trigger on install_audit), V36 (install_audit org_id non-null
--   CHECK), V34 (devices UNIQUE(id, org_id)), V46 (commands hub), V47 (catalog
--   hub), V44/V45 (flip pattern), manifest §3 step 8.

-- Phase 4: preflight fail-loud (3 edges — every install_audit row must have a
-- canonical (parent_id, org_id) parent).
DO $$
DECLARE bad BIGINT;
BEGIN
    -- install_audit -> endpoint_devices(id, org_id)
    SELECT count(*) INTO bad FROM endpoint_install_audit a
        WHERE NOT EXISTS (SELECT 1 FROM endpoint_devices d WHERE d.id = a.device_id AND d.org_id = a.org_id);
    IF bad > 0 THEN RAISE EXCEPTION 'V48 preflight: % install_audit rows have no endpoint_devices(id, org_id) parent', bad; END IF;
    -- install_audit -> endpoint_commands(id, org_id)
    SELECT count(*) INTO bad FROM endpoint_install_audit a
        WHERE NOT EXISTS (SELECT 1 FROM endpoint_commands c WHERE c.id = a.command_id AND c.org_id = a.org_id);
    IF bad > 0 THEN RAISE EXCEPTION 'V48 preflight: % install_audit rows have no endpoint_commands(id, org_id) parent', bad; END IF;
    -- install_audit -> endpoint_software_catalog_items(id, org_id)
    SELECT count(*) INTO bad FROM endpoint_install_audit a
        WHERE NOT EXISTS (SELECT 1 FROM endpoint_software_catalog_items ci WHERE ci.id = a.catalog_item_id AND ci.org_id = a.org_id);
    IF bad > 0 THEN RAISE EXCEPTION 'V48 preflight: % install_audit rows have no catalog(id, org_id) parent', bad; END IF;
END $$;

-- Phase 5: FK flips — atomic add-NOT VALID + VALIDATE + drop-old. ON DELETE
-- preserved per edge (device CASCADE, command CASCADE, catalog RESTRICT).
ALTER TABLE endpoint_install_audit
    ADD CONSTRAINT install_audit_device_org_fk FOREIGN KEY (device_id, org_id)
        REFERENCES endpoint_devices (id, org_id) ON DELETE CASCADE NOT VALID;
ALTER TABLE endpoint_install_audit VALIDATE CONSTRAINT install_audit_device_org_fk;
ALTER TABLE endpoint_install_audit DROP CONSTRAINT fk_endpoint_install_audit_device;

ALTER TABLE endpoint_install_audit
    ADD CONSTRAINT install_audit_command_org_fk FOREIGN KEY (command_id, org_id)
        REFERENCES endpoint_commands (id, org_id) ON DELETE CASCADE NOT VALID;
ALTER TABLE endpoint_install_audit VALIDATE CONSTRAINT install_audit_command_org_fk;
ALTER TABLE endpoint_install_audit DROP CONSTRAINT fk_endpoint_install_audit_command;

ALTER TABLE endpoint_install_audit
    ADD CONSTRAINT install_audit_catalog_org_fk FOREIGN KEY (catalog_item_id, org_id)
        REFERENCES endpoint_software_catalog_items (id, org_id) ON DELETE RESTRICT NOT VALID;
ALTER TABLE endpoint_install_audit VALIDATE CONSTRAINT install_audit_catalog_org_fk;
ALTER TABLE endpoint_install_audit DROP CONSTRAINT fk_endpoint_install_audit_catalog;

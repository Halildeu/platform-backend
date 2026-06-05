-- V50 — Faz 21.1 Cleanup C4 step-10 — remaining 2 catalog-consumer FK flips
--       (FOUNDATION + FLIP variant; completes the manifest §2 12-FK arc)
--
-- BOUNDARY: org-expands the last 2 catalog consumers (created in V10 + V31, NOT
-- in the V29 7-table set, so neither carries org_id) and flips their catalog FK
-- to org-composite, now that the catalog hub (V47) carries UNIQUE(id, org_id).
-- These 2 FKs are the remainder of the board #469 12-FK list that the C4
-- sequencing steps 6-9 (V46 commands hub, V47 catalog hub, V48 install_audit,
-- V49 uninstall family = 10 FKs) did not cover:
--   endpoint_software_compliance_policy_items.(catalog_item_id) -> catalog  CASCADE
--   catalog_uninstall_settings_change_requests.(catalog_item_id) -> catalog  NO ACTION
-- Both consumers FK ONLY to catalog (no command/device edge), so V50 depends
-- solely on the V47 catalog hub. Also swaps compliance_policy_items' business
-- unique (tenant_id, catalog_item_id) -> (org_id, catalog_item_id) AND cuscr's
-- open-request partial-unique index uq_catalog_unins_change_one_open
-- (tenant_id, catalog_item_id, field) -> (org_id, catalog_item_id, field), for
-- the same single-arbiter canonicalization as V47 catalog / V49 uninstall
-- partial uniques. No tenant_id drop (A6); reads tenant-keyed (A5). Neither
-- table needs UNIQUE(id, org_id) (nothing FKs to them).
--
-- WHY SOUND: child org_id NON-NULL (VALIDATED CHECK here) + catalog_item_id
-- NOT NULL; catalog UNIQUE(id, org_id) + org_id NOT NULL (V47 LIVE). MATCH
-- SIMPLE composite FK then rejects cross-org rows (23503). Every row is
-- canonical (org_id = tenant_id), so the flips + the business-unique swap are
-- lossless; P4 preflight is fail-loud. compliance_policy_items keeps ON DELETE
-- CASCADE; cuscr keeps NO ACTION; the cuscr maker-checker / approval-pair /
-- field / state CHECKs are untouched.
--
-- LOCK BUDGET: plain transactional ALTERs. endpoint-admin not yet in prod;
-- Flyway runs at bootstrap before traffic; testai compliance_policy_items = 0
-- rows, cuscr = 1 row.
--
-- LIVE EVIDENCE (testai, read-only, immediately before authoring): neither table
--   has org_id; compliance_policy_items = 0 rows; cuscr = 1 row; both catalog FKs
--   are still (catalog_item_id, tenant_id) -> catalog(id, tenant_id)
--   [compliance CASCADE, cuscr NO ACTION]; catalog carries UNIQUE(id, org_id).
--
-- References: V10 (compliance_policy_items create + business unique), V31 (cuscr
--   create), V29 (compat trigger fn), V47 (catalog hub UNIQUE(id, org_id) +
--   business-unique swap pattern), V44-V49 (machinery/flip pattern), board #469
--   12-FK list. Codex thread 019e94bc.

-- ════════════════════════════════════════════════════════════════════
-- Phase 1: org_id machinery — endpoint_software_compliance_policy_items.
-- ════════════════════════════════════════════════════════════════════
ALTER TABLE endpoint_software_compliance_policy_items ADD COLUMN IF NOT EXISTS org_id UUID;
UPDATE endpoint_software_compliance_policy_items SET org_id = tenant_id WHERE org_id IS NULL AND tenant_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_endpoint_compliance_policy_items_org_id ON endpoint_software_compliance_policy_items(org_id);
DROP TRIGGER IF EXISTS endpoint_compliance_policy_items_org_id_compat ON endpoint_software_compliance_policy_items;
CREATE TRIGGER endpoint_compliance_policy_items_org_id_compat BEFORE INSERT OR UPDATE ON endpoint_software_compliance_policy_items
    FOR EACH ROW EXECUTE FUNCTION endpoint_org_id_compat_fill();

-- ════════════════════════════════════════════════════════════════════
-- Phase 2: org_id machinery — catalog_uninstall_settings_change_requests.
-- ════════════════════════════════════════════════════════════════════
ALTER TABLE catalog_uninstall_settings_change_requests ADD COLUMN IF NOT EXISTS org_id UUID;
UPDATE catalog_uninstall_settings_change_requests SET org_id = tenant_id WHERE org_id IS NULL AND tenant_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_catalog_unins_change_org_id ON catalog_uninstall_settings_change_requests(org_id);
DROP TRIGGER IF EXISTS catalog_unins_change_org_id_compat ON catalog_uninstall_settings_change_requests;
CREATE TRIGGER catalog_unins_change_org_id_compat BEFORE INSERT OR UPDATE ON catalog_uninstall_settings_change_requests
    FOR EACH ROW EXECUTE FUNCTION endpoint_org_id_compat_fill();

-- ════════════════════════════════════════════════════════════════════
-- Phase 3: match + non-null CHECK (NOT VALID + VALIDATE) — both tables.
-- ════════════════════════════════════════════════════════════════════
ALTER TABLE endpoint_software_compliance_policy_items ADD CONSTRAINT endpoint_compliance_policy_items_org_id_match CHECK (org_id IS NULL OR org_id = tenant_id) NOT VALID;
ALTER TABLE endpoint_software_compliance_policy_items VALIDATE CONSTRAINT endpoint_compliance_policy_items_org_id_match;
ALTER TABLE endpoint_software_compliance_policy_items ADD CONSTRAINT endpoint_compliance_policy_items_org_id_not_null CHECK (org_id IS NOT NULL) NOT VALID;
ALTER TABLE endpoint_software_compliance_policy_items VALIDATE CONSTRAINT endpoint_compliance_policy_items_org_id_not_null;

ALTER TABLE catalog_uninstall_settings_change_requests ADD CONSTRAINT catalog_unins_change_org_id_match CHECK (org_id IS NULL OR org_id = tenant_id) NOT VALID;
ALTER TABLE catalog_uninstall_settings_change_requests VALIDATE CONSTRAINT catalog_unins_change_org_id_match;
ALTER TABLE catalog_uninstall_settings_change_requests ADD CONSTRAINT catalog_unins_change_org_id_not_null CHECK (org_id IS NOT NULL) NOT VALID;
ALTER TABLE catalog_uninstall_settings_change_requests VALIDATE CONSTRAINT catalog_unins_change_org_id_not_null;

-- ════════════════════════════════════════════════════════════════════
-- Phase 4: preflight fail-loud — org invariants + 2 FK parent existence +
-- compliance_policy_items business-unique dup guard.
-- ════════════════════════════════════════════════════════════════════
DO $$
DECLARE bad BIGINT;
BEGIN
    SELECT count(*) INTO bad FROM endpoint_software_compliance_policy_items WHERE org_id IS NULL OR org_id <> tenant_id;
    IF bad > 0 THEN RAISE EXCEPTION 'V50 preflight: % compliance_policy_items rows with NULL/mismatched org_id', bad; END IF;
    SELECT count(*) INTO bad FROM catalog_uninstall_settings_change_requests WHERE org_id IS NULL OR org_id <> tenant_id;
    IF bad > 0 THEN RAISE EXCEPTION 'V50 preflight: % cuscr rows with NULL/mismatched org_id', bad; END IF;

    SELECT count(*) INTO bad FROM endpoint_software_compliance_policy_items p
        WHERE NOT EXISTS (SELECT 1 FROM endpoint_software_catalog_items ci WHERE ci.id = p.catalog_item_id AND ci.org_id = p.org_id);
    IF bad > 0 THEN RAISE EXCEPTION 'V50 preflight: % compliance_policy_items rows have no catalog(id, org_id) parent', bad; END IF;
    SELECT count(*) INTO bad FROM catalog_uninstall_settings_change_requests r
        WHERE NOT EXISTS (SELECT 1 FROM endpoint_software_catalog_items ci WHERE ci.id = r.catalog_item_id AND ci.org_id = r.org_id);
    IF bad > 0 THEN RAISE EXCEPTION 'V50 preflight: % cuscr rows have no catalog(id, org_id) parent', bad; END IF;

    SELECT count(*) INTO bad FROM (SELECT 1 FROM endpoint_software_compliance_policy_items GROUP BY org_id, catalog_item_id HAVING count(*) > 1) d;
    IF bad > 0 THEN RAISE EXCEPTION 'V50 preflight: % duplicate (org_id, catalog_item_id) compliance_policy_items groups', bad; END IF;
    -- cuscr open-change-request partial-unique dup guard (org-keyed predicate)
    SELECT count(*) INTO bad FROM (SELECT 1 FROM catalog_uninstall_settings_change_requests WHERE state IN ('PROPOSED', 'APPROVED') GROUP BY org_id, catalog_item_id, field HAVING count(*) > 1) d;
    IF bad > 0 THEN RAISE EXCEPTION 'V50 preflight: % duplicate open (org_id, catalog_item_id, field) cuscr groups', bad; END IF;
END $$;

-- ════════════════════════════════════════════════════════════════════
-- Phase 5: business arbiter single-arbiter swaps.
--   (a) compliance_policy_items UNIQUE constraint (tenant_id, catalog_item_id)
--       -> (org_id, catalog_item_id).
--   (b) cuscr partial-unique INDEX uq_catalog_unins_change_one_open
--       (tenant_id, catalog_item_id, field) -> (org_id, catalog_item_id, field)
--       WHERE state IN ('PROPOSED','APPROVED') — the one-open-request-per-key
--       arbiter (V31). Partial unique => bare INDEX (CREATE/DROP/RENAME), not a
--       table constraint. Same single-arbiter discipline as V49's uninstall
--       partial uniques (Codex 019e94bc post-impl catch).
-- ════════════════════════════════════════════════════════════════════
ALTER TABLE endpoint_software_compliance_policy_items ADD CONSTRAINT uq_endpoint_software_compliance_policy_items_org_catalog UNIQUE (org_id, catalog_item_id);
ALTER TABLE endpoint_software_compliance_policy_items DROP CONSTRAINT uq_endpoint_software_compliance_policy_items_tenant_catalog;

CREATE UNIQUE INDEX uq_catalog_unins_change_one_open_org_tmp
    ON catalog_uninstall_settings_change_requests (org_id, catalog_item_id, field)
    WHERE state IN ('PROPOSED', 'APPROVED');
DROP INDEX uq_catalog_unins_change_one_open;
ALTER INDEX uq_catalog_unins_change_one_open_org_tmp RENAME TO uq_catalog_unins_change_one_open;

-- ════════════════════════════════════════════════════════════════════
-- Phase 6: FK flips (add-NOT VALID + VALIDATE + drop-old). ON DELETE preserved:
-- compliance_policy_items CASCADE, cuscr NO ACTION.
-- ════════════════════════════════════════════════════════════════════
ALTER TABLE endpoint_software_compliance_policy_items
    ADD CONSTRAINT compliance_policy_items_catalog_org_fk FOREIGN KEY (catalog_item_id, org_id)
        REFERENCES endpoint_software_catalog_items (id, org_id) ON DELETE CASCADE NOT VALID;
ALTER TABLE endpoint_software_compliance_policy_items VALIDATE CONSTRAINT compliance_policy_items_catalog_org_fk;
ALTER TABLE endpoint_software_compliance_policy_items DROP CONSTRAINT fk_endpoint_software_compliance_policy_items_catalog;

ALTER TABLE catalog_uninstall_settings_change_requests
    ADD CONSTRAINT cuscr_catalog_org_fk FOREIGN KEY (catalog_item_id, org_id)
        REFERENCES endpoint_software_catalog_items (id, org_id) NOT VALID;
ALTER TABLE catalog_uninstall_settings_change_requests VALIDATE CONSTRAINT cuscr_catalog_org_fk;
ALTER TABLE catalog_uninstall_settings_change_requests DROP CONSTRAINT fk_catalog_unins_change_catalog;

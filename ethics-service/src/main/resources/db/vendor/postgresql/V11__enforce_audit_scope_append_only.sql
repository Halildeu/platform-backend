-- PostgreSQL hard backstop for Faz 35 ES-302 (#884).
-- H2 source tests exercise the application contract; the production database engine
-- proves the trigger and the coverage assertion here.

-- A classification that can be edited afterwards is not evidence of anything. This table
-- carries the same append-only guarantee as the ledger it describes; without it, the
-- cheapest way to fake a clean erasure receipt would be to rewrite the scope rows rather
-- than the audit rows.
CREATE OR REPLACE FUNCTION ethics_audit_scope_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'ethics_audit_scope is append-only (attempted %)', TG_OP;
END;
$$;

CREATE TRIGGER trg_ethics_audit_scope_append_only
    BEFORE UPDATE OR DELETE ON ethics_audit_scope
    FOR EACH ROW EXECUTE FUNCTION ethics_audit_scope_append_only();

ALTER TABLE ethics_audit_scope ENABLE ALWAYS TRIGGER trg_ethics_audit_scope_append_only;

-- Coverage is asserted, not assumed: a backfill that silently skipped rows would leave a
-- hole exactly where the erasure claim will later be made. UNRESOLVED rows are allowed
-- through — they are an honest record of what could not be determined — but a MISSING row
-- means the insert above did not see the whole ledger, which is a defect in this
-- migration and must stop it.
DO $$
DECLARE uncovered bigint;
BEGIN
    SELECT count(*) INTO uncovered
      FROM ethics_worm_audit w
     WHERE NOT EXISTS (SELECT 1 FROM ethics_audit_scope s WHERE s.worm_audit_id = w.id);
    IF uncovered > 0 THEN
        RAISE EXCEPTION 'audit scope backfill left % worm row(s) unclassified', uncovered;
    END IF;
END;
$$;


-- PostgreSQL hard backstop for Faz 35 ES-2 (#3271).
-- H2 source tests exercise the application contract; the production database engine
-- proves the immutability and the NULL-folded scope uniqueness here.

-- A template version that has been sent is a historical fact, not an editable row: the
-- ledger's "template X version N" must resolve to the same words years later. Editing
-- means inserting the next version.
CREATE OR REPLACE FUNCTION ethics_ack_template_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'ethics_ack_template is append-only (attempted %)', TG_OP;
END;
$$;

CREATE TRIGGER trg_ethics_ack_template_append_only
    BEFORE UPDATE OR DELETE ON ethics_ack_template
    FOR EACH ROW EXECUTE FUNCTION ethics_ack_template_append_only();

-- One version sequence per (org, category) scope. COALESCE folds the NULLs so platform
-- defaults and org-wide templates get the same uniqueness a concrete scope has — two
-- concurrent "version 2" inserts for the same scope must not both land.
CREATE UNIQUE INDEX ux_ethics_ack_template_scope_version
    ON ethics_ack_template (
        COALESCE(org_id, '00000000-0000-0000-0000-000000000000'::uuid),
        COALESCE(category, '*'),
        version
    );

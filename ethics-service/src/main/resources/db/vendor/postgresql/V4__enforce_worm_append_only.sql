-- PostgreSQL hard backstop for Faz 35 ES-207.
-- H2 source tests exercise the application contract; the production database
-- engine proves this trigger with a Testcontainers integration test.

CREATE OR REPLACE FUNCTION ethics_worm_audit_append_only()
    RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION
        'ethics_worm_audit is append-only: % rejected', TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ethics_worm_audit_append_only
    BEFORE UPDATE OR DELETE ON ethics_worm_audit
    FOR EACH ROW EXECUTE FUNCTION ethics_worm_audit_append_only();

-- PostgreSQL hard backstop for the ES-104 custody lineage.
-- Derivation manifests are append-only; a re-sanitization creates a new row.

CREATE OR REPLACE FUNCTION ethics_evidence_derivation_append_only()
    RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION
        'ethics_evidence_derivations is append-only: % rejected', TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ethics_evidence_derivation_append_only
    BEFORE UPDATE OR DELETE ON ethics_evidence_derivations
    FOR EACH ROW EXECUTE FUNCTION ethics_evidence_derivation_append_only();

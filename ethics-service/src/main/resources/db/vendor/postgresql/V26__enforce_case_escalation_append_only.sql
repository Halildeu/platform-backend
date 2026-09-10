-- ES-301 (#882), PostgreSQL half of V25. H2 source tests exercise the application contract;
-- the production engine proves immutability here.
--
-- An escalation that has happened cannot be softened or removed. Meeting the obligation
-- afterwards stops further levels; it does not un-happen the ones already recorded, because
-- "when did the organisation know" is exactly the question these rows exist to answer.
CREATE OR REPLACE FUNCTION ethics_case_escalation_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'ethics_case_escalation is append-only (attempted %)', TG_OP;
END;
$$;

CREATE TRIGGER trg_ethics_case_escalation_append_only
    BEFORE UPDATE OR DELETE ON ethics_case_escalation
    FOR EACH ROW EXECUTE FUNCTION ethics_case_escalation_append_only();

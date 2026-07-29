-- Faz 35 ES-403 — org-scoped audit rows are not "could not be determined" (#885).
--
-- V10 knew two kinds of aggregate: a case and an attachment. Anything else was UNRESOLVED,
-- which is an honest label for exactly one situation — the parent was already gone when the
-- classification ran. It is the wrong label for an event that never had a case at all.
--
-- Subscriptions bring the first of those. A grant is a commercial fact about an organisation,
-- it is audited into the same append-only ledger, and its aggregate is the subscription row.
-- Left as-is it would land as UNRESOLVED and read, later, as a row whose case could not be
-- established — which would make erasure receipts look incomplete for rows that were never
-- case-scoped in the first place. Two very different things must not share one word.
--
-- So the vocabulary gains ORG, and the root-case rule follows it: a root case is required for
-- CASE and ATTACHMENT, and must be absent for ORG and UNRESOLVED. The rule stays an
-- equivalence rather than a pair of one-way checks, so neither side can drift: an ORG row
-- carrying a case, and a CASE row missing one, are both still rejected.

ALTER TABLE ethics_audit_scope DROP CONSTRAINT ethics_audit_scope_type_known;
ALTER TABLE ethics_audit_scope ADD CONSTRAINT ethics_audit_scope_type_known
    CHECK (aggregate_type IN ('CASE', 'ATTACHMENT', 'ORG', 'UNRESOLVED'));

ALTER TABLE ethics_audit_scope DROP CONSTRAINT ethics_audit_scope_root_present;
ALTER TABLE ethics_audit_scope ADD CONSTRAINT ethics_audit_scope_root_present
    CHECK ((aggregate_type IN ('ORG', 'UNRESOLVED')) = (root_case_id IS NULL));

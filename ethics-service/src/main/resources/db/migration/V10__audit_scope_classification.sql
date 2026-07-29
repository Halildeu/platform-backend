-- Faz 35 ES-302 — classify every audit row before erasure makes it unclassifiable (#884).
--
-- `aggregate_id` is polymorphic with no discriminator: a case event carries the case id,
-- an evidence event carries the ATTACHMENT id. Today the type can be derived by joining
-- the parent tables, and that is how the case timeline was fixed (platform-backend#1004).
--
-- But erasure destroys the very evidence the derivation depends on. Once a case and its
-- attachments are gone, the audit rows that outlive them can no longer be attributed to
-- anything: "every record of this case was erased" stops being a checkable claim at
-- exactly the moment it needs checking. And `ethics_worm_audit` is append-only by trigger,
-- so those rows can never be annotated after the fact.
--
-- So the classification is captured NOW, into a separate table, while the parents are
-- still alive to answer.
--
-- Postgres-only pieces (the append-only trigger and the coverage assertion) live in
-- db/vendor/postgresql/V11, the lane this repo already uses for plpgsql; the H2 source
-- tests exercise the same invariants through EthicsAuditScopeTest.
--
-- The mapping is aggregate_id -> (type, root case). The root case is the part that
-- matters and the part a bare type column would miss: knowing a row is an ATTACHMENT
-- event does not say WHICH case it belonged to, and a case-scoped erasure claim needs
-- exactly that.

CREATE TABLE ethics_audit_scope (
    worm_audit_id   uuid PRIMARY KEY REFERENCES ethics_worm_audit (id),
    aggregate_id    uuid        NOT NULL,
    aggregate_type  varchar(20) NOT NULL,
    -- Nullable only for UNRESOLVED: a row whose parent was already gone when this ran.
    -- Recorded as such rather than guessed, because a wrong root case in an erasure
    -- receipt is worse than an admitted gap.
    root_case_id    uuid,
    classified_at   timestamp with time zone NOT NULL DEFAULT now(),
    classified_by   varchar(80) NOT NULL,
    CONSTRAINT ethics_audit_scope_type_known
        CHECK (aggregate_type IN ('CASE', 'ATTACHMENT', 'UNRESOLVED')),
    CONSTRAINT ethics_audit_scope_root_present
        CHECK ((aggregate_type = 'UNRESOLVED') = (root_case_id IS NULL))
);

CREATE INDEX ethics_audit_scope_root_case_idx ON ethics_audit_scope (root_case_id);
CREATE INDEX ethics_audit_scope_aggregate_idx ON ethics_audit_scope (aggregate_id);

-- Backfill. Order matters: a case id is checked first, because the two id spaces are
-- disjoint in practice but nothing enforces that, and a collision must resolve to the
-- case rather than silently to an attachment.
INSERT INTO ethics_audit_scope (worm_audit_id, aggregate_id, aggregate_type, root_case_id, classified_by)
SELECT w.id,
       w.aggregate_id,
       CASE
           WHEN c.id IS NOT NULL THEN 'CASE'
           WHEN e.id IS NOT NULL THEN 'ATTACHMENT'
           ELSE 'UNRESOLVED'
       END,
       COALESCE(c.id, e.case_id),
       'V10__audit_scope_classification'
  FROM ethics_worm_audit w
  LEFT JOIN ethics_cases c ON c.id = w.aggregate_id
  LEFT JOIN ethics_evidence_attachments e ON e.id = w.aggregate_id;

COMMENT ON TABLE ethics_audit_scope IS
    'Which case each append-only audit row belongs to, captured while the parent rows '
    'still exist. Erasure removes the parents, so this mapping cannot be reconstructed '
    'afterwards; it is what makes "every record of this case was erased" checkable. '
    'Append-only, like the ledger it describes.';

-- ES-213 (#3375) — the partial forms of the two working-set indexes.
--
-- V22 creates portable versions of both because H2 backs the fast test slices and rejects
-- `CREATE INDEX ... WHERE` as a syntax error before any assertion runs. Postgres carries
-- the whole cell in production shape, and there the predicate is the point: both reads ask
-- for the *outstanding* rows, and a partial index keeps them proportional to the backlog
-- rather than to the history.
--
-- The full indexes stay. Dropping them would leave H2 without one, and the cost of a
-- duplicate index on a table this size is not worth a vendor-conditional teardown.

CREATE INDEX ix_sanctions_pending_partial
    ON ethics_case_sanctions (org_id, applied_at)
    WHERE applied_at IS NULL;

CREATE INDEX ix_retaliation_due_partial
    ON ethics_retaliation_checks (org_id, due_at)
    WHERE closed_at IS NULL;

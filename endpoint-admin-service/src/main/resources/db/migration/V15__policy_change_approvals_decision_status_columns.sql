-- Wave-12 PR-5 Codex iter-1 absorb:
--
-- 1. Decision rows now carry previousStatus + newStatus + evidenceRefs to
--    match the design-system DecisionRecordBase contract. previousStatus
--    is the parent request status BEFORE the decision; newStatus is the
--    status AFTER (for DELEGATE/ATTEST these are equal — the request
--    stays open).
-- 2. Parent status CHECK widens to include WITHDRAWN (proposer retract);
--    decision-side CHECKs include the same set so a future withdraw
--    transition can be persisted without a third migration.
-- 3. Per-decision evidenceRefs (string array of supporting links / ticket
--    ids) gives reviewers attachment surface.
--
-- Migration is safe even if V14 has been applied to a live cluster: the
-- ALTER TABLE … ADD COLUMN with a transient DEFAULT then DROP DEFAULT
-- pattern keeps backfill atomic (no rows exist in the pilot yet — the
-- DEFAULT only satisfies the NOT NULL contract for the transient instant
-- between ADD and DROP).

ALTER TABLE policy_change_approvals
    DROP CONSTRAINT IF EXISTS ck_policy_change_approvals_status;
ALTER TABLE policy_change_approvals
    ADD CONSTRAINT ck_policy_change_approvals_status
        CHECK (status IN ('PENDING', 'IN_REVIEW', 'APPROVED', 'REJECTED',
                          'WITHDRAWN', 'EXPIRED'));

ALTER TABLE policy_change_approval_decisions
    ADD COLUMN previous_status VARCHAR(32) NOT NULL DEFAULT 'PENDING';
ALTER TABLE policy_change_approval_decisions
    ALTER COLUMN previous_status DROP DEFAULT;

ALTER TABLE policy_change_approval_decisions
    ADD COLUMN new_status VARCHAR(32) NOT NULL DEFAULT 'PENDING';
ALTER TABLE policy_change_approval_decisions
    ALTER COLUMN new_status DROP DEFAULT;

ALTER TABLE policy_change_approval_decisions
    ADD CONSTRAINT ck_policy_change_approval_decisions_previous_status
        CHECK (previous_status IN ('PENDING', 'IN_REVIEW', 'APPROVED',
                                   'REJECTED', 'WITHDRAWN', 'EXPIRED'));

ALTER TABLE policy_change_approval_decisions
    ADD CONSTRAINT ck_policy_change_approval_decisions_new_status
        CHECK (new_status IN ('PENDING', 'IN_REVIEW', 'APPROVED',
                              'REJECTED', 'WITHDRAWN', 'EXPIRED'));

ALTER TABLE policy_change_approval_decisions
    ADD COLUMN evidence_refs JSONB;

-- ES-301 (#882). An escalation level reached on one obligation of one case.
--
-- A breach is computed from the case's timestamps and changes meaning the moment the
-- obligation is met; this table is what an organisation is later asked for — not whether it
-- was behind, but when it knew and how far behind it was at that moment. So the row carries
-- the deadline it was measured against, the threshold this level's step set under the policy
-- in force at the time, and the instant it was recorded. Nothing else: no actor (the clock
-- did it), no free text (free text collects names).
--
-- UNIQUE (case_id, obligation, level) is the concurrency guard, not a tidiness constraint.
-- The service has no distributed lock; the escalation sweeper runs in every replica. The
-- sweeper serialises on the case row (SELECT ... FOR UPDATE) so two replicas normally see
-- each other's rows; this index is the backstop for the path that does not. The loser's
-- transaction fails here and is skipped, so its audit event rolls back with it. The
-- PostgreSQL half (V26) makes the row immutable.
CREATE TABLE ethics_case_escalation (
    id UUID PRIMARY KEY,
    case_id UUID NOT NULL,
    org_id UUID NOT NULL,
    obligation VARCHAR(20) NOT NULL,
    level INTEGER NOT NULL,
    due_at TIMESTAMP WITH TIME ZONE NOT NULL,
    threshold_at TIMESTAMP WITH TIME ZONE NOT NULL,
    escalated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_ethics_escalation_obligation CHECK (obligation IN ('ACKNOWLEDGEMENT', 'FEEDBACK')),
    CONSTRAINT ck_ethics_escalation_level CHECK (level >= 1),
    -- A step sits at or after the deadline, never before: a threshold inside the window
    -- would escalate an obligation that could still be met.
    CONSTRAINT ck_ethics_escalation_threshold CHECK (threshold_at >= due_at),
    -- Recorded after the threshold has passed, never before.
    CONSTRAINT ck_ethics_escalation_after_threshold CHECK (escalated_at > threshold_at),
    CONSTRAINT ux_ethics_escalation_level UNIQUE (case_id, obligation, level)
);

CREATE INDEX ix_ethics_escalation_org ON ethics_case_escalation (org_id, escalated_at);

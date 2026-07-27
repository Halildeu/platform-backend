-- Faz 35 ES-301A — record the two things a whistleblowing case is judged on.
--
-- A case could be `NEW`, `IN_REVIEW` or `CLOSED` and nothing else. That
-- vocabulary cannot express either obligation the standards actually measure:
--
--   EU 2019/1937 art. 9(1)(b) — acknowledge receipt to the reporter within
--                               seven days. There was no field for when.
--   EU 2019/1937 art. 9(1)(f) — give feedback within three months. Feedback
--                               about what? No outcome was ever recorded.
--   ISO 37002                 — receive, assess, address, conclude. `IN_REVIEW`
--                               collapses assess and address into one word.
--
-- Measured on the test cell before this migration: 160 cases, 131 `NEW`,
-- 29 `IN_REVIEW`, **zero** `CLOSED`. The lifecycle had never once concluded,
-- which is unsurprising — concluding meant setting a string, and a string that
-- carries no finding is not a conclusion anyone would bother to write.
--
-- `acknowledged_at` is deliberately NOT settable through the API. It is
-- stamped by the system when the first reporter-visible staff message is
-- sent, so the record of having acknowledged and the act of acknowledging are
-- the same event. A field an operator can set independently would let the
-- service claim compliance with art. 9(1)(b) while the reporter heard nothing.
--
-- The backfill below applies that same rule to history rather than inventing
-- timestamps: a case is acknowledged as of its first reporter-visible staff
-- message, and cases without one stay null — correctly, because those
-- reporters were never contacted.

ALTER TABLE ethics_cases ADD COLUMN acknowledged_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE ethics_cases ADD COLUMN outcome VARCHAR(40);
ALTER TABLE ethics_cases ADD COLUMN closed_at TIMESTAMP WITH TIME ZONE;

UPDATE ethics_cases SET acknowledged_at = (
    SELECT MIN(m.created_at) FROM ethics_messages m
    WHERE m.case_id = ethics_cases.id
      AND m.author_type = 'STAFF'
      AND m.visibility = 'REPORTER_VISIBLE');

-- `IN_REVIEW` becomes `ASSESSING`, not `INVESTIGATING`. Every one of these 29
-- cases has a staff reply, but a reply can as easily be a clarifying question
-- as a step into investigation. `ASSESSING` is the weaker claim and the only
-- one the evidence supports; staff can move a case forward themselves.
UPDATE ethics_cases SET status = 'ASSESSING' WHERE status = 'IN_REVIEW';

ALTER TABLE ethics_cases DROP CONSTRAINT ck_ethics_case_status;
ALTER TABLE ethics_cases ADD CONSTRAINT ck_ethics_case_status
    CHECK (status IN ('NEW','ASSESSING','INVESTIGATING','CLOSED'));

ALTER TABLE ethics_cases ADD CONSTRAINT ck_ethics_case_outcome
    CHECK (outcome IS NULL OR outcome IN (
        'SUBSTANTIATED','PARTIALLY_SUBSTANTIATED','UNSUBSTANTIATED',
        'OUT_OF_SCOPE','REFERRED','WITHDRAWN'));

-- Conclusion is all-or-nothing. A closed case carries a finding and a date; an
-- open one carries neither. Enforced here as well as in the service so that a
-- future writer that bypasses the service still cannot leave a case closed
-- with no finding — the state that made `CLOSED` meaningless to begin with.
ALTER TABLE ethics_cases ADD CONSTRAINT ck_ethics_case_closure
    CHECK ((status = 'CLOSED' AND outcome IS NOT NULL AND closed_at IS NOT NULL)
        OR (status <> 'CLOSED' AND outcome IS NULL AND closed_at IS NULL));

CREATE INDEX ix_ethics_cases_ack ON ethics_cases(org_id, acknowledged_at);

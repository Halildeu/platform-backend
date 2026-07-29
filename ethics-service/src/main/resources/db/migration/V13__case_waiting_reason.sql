-- ES-301. Why a case is waiting — recorded, and deliberately not subtracted from anything.
--
-- EU 2019/1937 starts the seven days at receipt and the three months at acknowledgement and
-- provides no suspension. A "paused" state that reduced its own duration would not be
-- measuring the obligation; it would be a way to make a breach disappear administratively,
-- in the product where that is least acceptable. So this table annotates and the deadline
-- stays where the law put it.
--
-- Append-only in shape as well as intent: a resume writes ended_at on the open row rather
-- than deleting it, so the history of what a case waited for survives.
CREATE TABLE ethics_case_waiting_reason (
    id UUID PRIMARY KEY,
    case_id UUID NOT NULL,
    org_id UUID NOT NULL,
    -- A closed vocabulary, not free text. Free text here would collect names: "waiting for
    -- Ahmet to answer" puts a person into a column nothing sanitises.
    reason VARCHAR(40) NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ended_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_ethics_waiting_reason CHECK (reason IN (
        'AWAITING_REPORTER',
        'AWAITING_EXTERNAL_AUTHORITY',
        'AWAITING_INTERNAL_INPUT'
    )),
    CONSTRAINT ck_ethics_waiting_window CHECK (ended_at IS NULL OR ended_at >= started_at)
);

CREATE INDEX ix_ethics_waiting_case ON ethics_case_waiting_reason (case_id, started_at);

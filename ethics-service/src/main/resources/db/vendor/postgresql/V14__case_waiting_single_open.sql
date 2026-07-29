-- ES-301, PostgreSQL half of V13. A partial unique index needs a WHERE clause, which H2
-- does not accept, so the constraint that matters most lives here.
--
-- One open wait per case. Without it a second "pause" while one is already open makes the
-- history unreadable — and a case waiting for two things at once has no meaning at this
-- level of detail. Enforced by the database rather than by the service, because the service
-- is not the only thing that will ever write this table.
CREATE UNIQUE INDEX ux_ethics_waiting_open
    ON ethics_case_waiting_reason (case_id)
    WHERE ended_at IS NULL;

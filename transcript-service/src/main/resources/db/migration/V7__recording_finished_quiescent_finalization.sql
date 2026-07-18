-- Durable recording-finished inbox and restart-safe transcript quiescence state.

ALTER TABLE transcript_session_associations
    ADD COLUMN finalization_state VARCHAR(24) NOT NULL DEFAULT 'AWAITING_FINISH',
    ADD COLUMN finalization_cycle_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN recording_finished_at TIMESTAMPTZ,
    ADD COLUMN finish_observed_at TIMESTAMPTZ,
    ADD COLUMN last_content_changed_at TIMESTAMPTZ,
    ADD COLUMN min_wait_at TIMESTAMPTZ,
    ADD COLUMN quiescence_due_at TIMESTAMPTZ,
    ADD COLUMN max_wait_at TIMESTAMPTZ,
    ADD COLUMN finalization_error_code VARCHAR(64);

-- Explicit finalization existed before the quiescence state machine. Preserve
-- those immutable occurrences as completed cycles instead of enrolling them a
-- second time when a delayed recording-finished marker arrives.
UPDATE transcript_session_associations
SET finalization_state = 'FINALIZED',
    finalization_cycle_version = finalization_version
WHERE finalization_version > 0;

ALTER TABLE transcript_session_associations
    ADD CONSTRAINT transcript_session_association_finalization_state
        CHECK (finalization_state IN ('AWAITING_FINISH', 'QUIESCING', 'FINALIZED', 'TIMED_OUT')),
    ADD CONSTRAINT transcript_session_association_cycle_version
        CHECK (finalization_cycle_version >= finalization_version),
    ADD CONSTRAINT transcript_session_association_finalized_cycle
        CHECK (finalization_state <> 'FINALIZED'
               OR finalization_cycle_version = finalization_version),
    ADD CONSTRAINT transcript_session_association_quiescence_shape
        CHECK ((finalization_state = 'QUIESCING'
                AND recording_finished_at IS NOT NULL
                AND finish_observed_at IS NOT NULL
                AND min_wait_at IS NOT NULL
                AND quiescence_due_at IS NOT NULL
                AND max_wait_at IS NOT NULL)
            OR finalization_state <> 'QUIESCING');

CREATE INDEX idx_transcript_session_association_finalization_due
    ON transcript_session_associations (quiescence_due_at, id)
    WHERE status = 'RESOLVED' AND finalization_state = 'QUIESCING';

CREATE TABLE transcript_meeting_event_inbox (
    id                  UUID         NOT NULL PRIMARY KEY,
    event_key           VARCHAR(240) NOT NULL,
    event_type          VARCHAR(64)  NOT NULL,
    payload_sha256      VARCHAR(64)  NOT NULL,
    tenant_id           UUID         NOT NULL,
    org_id              UUID,
    meeting_id          UUID         NOT NULL,
    session_id          UUID         NOT NULL,
    source_session_id   VARCHAR(128) NOT NULL,
    received_at         TIMESTAMPTZ  NOT NULL,
    processed_at        TIMESTAMPTZ,

    CONSTRAINT uq_transcript_meeting_event_inbox_key UNIQUE (event_key),
    CONSTRAINT transcript_meeting_event_inbox_org_match
        CHECK (org_id IS NULL OR org_id = tenant_id),
    CONSTRAINT transcript_meeting_event_inbox_payload_hash
        CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT transcript_meeting_event_inbox_type
        CHECK (event_type = 'meeting.recording.finished'),
    CONSTRAINT transcript_meeting_event_inbox_source_format
        CHECK (source_session_id ~ '^SES-[A-Za-z0-9._:-]{1,124}$')
);

CREATE INDEX idx_transcript_meeting_event_inbox_scope
    ON transcript_meeting_event_inbox (tenant_id, meeting_id, session_id);

DROP TRIGGER IF EXISTS transcript_meeting_event_inbox_org_id_compat
    ON transcript_meeting_event_inbox;
CREATE TRIGGER transcript_meeting_event_inbox_org_id_compat
    BEFORE INSERT OR UPDATE ON transcript_meeting_event_inbox
    FOR EACH ROW EXECUTE FUNCTION transcript_org_id_compat_fill();

COMMENT ON TABLE transcript_meeting_event_inbox IS
    'Metadata-only idempotency ledger for recording-finished events; raw payload and transcript content are forbidden.';
COMMENT ON COLUMN transcript_meeting_event_inbox.payload_sha256 IS
    'SHA-256 of exact canonical event payload bytes; the payload itself is not retained.';

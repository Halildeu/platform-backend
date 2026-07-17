-- ============================================================================
-- V5 - explicit transcript finalization occurrences + service-local outbox
-- ============================================================================

CREATE TABLE transcript_finalizations (
    id                      UUID         NOT NULL PRIMARY KEY,
    tenant_id               UUID         NOT NULL,
    org_id                  UUID,
    meeting_id              UUID         NOT NULL,
    session_id              UUID         NOT NULL,
    finalization_version    BIGINT       NOT NULL,
    segment_count           INTEGER      NOT NULL,
    snapshot_sha256         VARCHAR(64)  NOT NULL,
    finalized_at            TIMESTAMPTZ  NOT NULL,
    created_at              TIMESTAMPTZ  NOT NULL,

    CONSTRAINT transcript_finalization_org_match
        CHECK (org_id IS NULL OR org_id = tenant_id),
    CONSTRAINT transcript_finalization_version_positive
        CHECK (finalization_version >= 1),
    CONSTRAINT transcript_finalization_segment_count_positive
        CHECK (segment_count >= 1),
    CONSTRAINT transcript_finalization_snapshot_format
        CHECK (snapshot_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT uq_transcript_finalization_occurrence
        UNIQUE (tenant_id, meeting_id, session_id, finalization_version)
);

CREATE INDEX idx_transcript_finalizations_session
    ON transcript_finalizations (tenant_id, meeting_id, session_id, finalization_version DESC);

DROP TRIGGER IF EXISTS transcript_finalization_org_id_compat ON transcript_finalizations;
CREATE TRIGGER transcript_finalization_org_id_compat
    BEFORE INSERT OR UPDATE ON transcript_finalizations
    FOR EACH ROW EXECUTE FUNCTION transcript_org_id_compat_fill();

CREATE TABLE transcript_event_outbox (
    id                  UUID         NOT NULL PRIMARY KEY,
    event_type          VARCHAR(64)  NOT NULL,
    aggregate_id        UUID         NOT NULL,
    meeting_id          UUID         NOT NULL,
    tenant_id           UUID         NOT NULL,
    org_id              UUID,
    payload             JSONB        NOT NULL,
    event_key           VARCHAR(240) NOT NULL,
    status              VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    claim_token         UUID,
    processing_owner    VARCHAR(128),
    claimed_at          TIMESTAMPTZ,
    lease_expires_at    TIMESTAMPTZ,
    attempts            INTEGER      NOT NULL DEFAULT 0,
    last_error          VARCHAR(128),
    next_attempt_at     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at          TIMESTAMPTZ  NOT NULL,
    published_at        TIMESTAMPTZ,
    updated_at          TIMESTAMPTZ  NOT NULL,
    version             BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT transcript_event_outbox_org_match
        CHECK (org_id IS NULL OR org_id = tenant_id),
    CONSTRAINT transcript_event_outbox_status
        CHECK (status IN ('PENDING', 'CLAIMED', 'PUBLISHED', 'DEAD')),
    CONSTRAINT transcript_event_outbox_attempts
        CHECK (attempts >= 0),
    CONSTRAINT uq_transcript_event_outbox_event_key UNIQUE (event_key)
);

CREATE INDEX idx_transcript_event_outbox_pending
    ON transcript_event_outbox (status, next_attempt_at, created_at, id)
    WHERE status = 'PENDING';
CREATE INDEX idx_transcript_event_outbox_lease
    ON transcript_event_outbox (lease_expires_at)
    WHERE status = 'CLAIMED';
CREATE INDEX idx_transcript_event_outbox_aggregate
    ON transcript_event_outbox (aggregate_id);
CREATE INDEX idx_transcript_event_outbox_org
    ON transcript_event_outbox (org_id);

DROP TRIGGER IF EXISTS transcript_event_outbox_org_id_compat ON transcript_event_outbox;
CREATE TRIGGER transcript_event_outbox_org_id_compat
    BEFORE INSERT OR UPDATE ON transcript_event_outbox
    FOR EACH ROW EXECUTE FUNCTION transcript_org_id_compat_fill();

COMMENT ON TABLE transcript_finalizations IS
    'Explicit canonical transcript finalization occurrences. Snapshot hash is stored locally and never emitted.';
COMMENT ON TABLE transcript_event_outbox IS
    'Transcript-service DB-local transactional outbox for thin meeting.event.v1 facts.';

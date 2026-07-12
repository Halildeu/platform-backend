-- V5 - Faz 24 #819 metadata-only audit for successful canonical result reads.
--
-- This table is intentionally unable to store Meeting Intelligence content.
-- It contains only the effective organization, authenticated accessor, target
-- identifiers, access classification, count, time, and an optional allowlisted
-- trace id. Result-access audit retention is independently configurable.

CREATE TABLE meeting_intelligence_result_access_audit (
    id                UUID         NOT NULL PRIMARY KEY,
    tenant_id         UUID         NOT NULL,
    org_id            UUID         NOT NULL,
    accessor_subject  VARCHAR(255) NOT NULL,
    meeting_id        UUID         NOT NULL,
    analysis_run_id   UUID         NOT NULL,
    access_type       VARCHAR(32)  NOT NULL,
    result_count      INTEGER      NOT NULL,
    trace_id          VARCHAR(64),
    accessed_at       TIMESTAMPTZ  NOT NULL,

    CONSTRAINT meeting_intelligence_result_access_org_match_ck
        CHECK (org_id = tenant_id),
    CONSTRAINT meeting_intelligence_result_access_subject_ck
        CHECK (char_length(btrim(accessor_subject)) BETWEEN 1 AND 255),
    CONSTRAINT meeting_intelligence_result_access_type_ck
        CHECK (access_type = 'CANONICAL_RESULT_READ'),
    CONSTRAINT meeting_intelligence_result_access_count_ck
        CHECK (result_count = 1),
    CONSTRAINT meeting_intelligence_result_access_trace_ck
        CHECK (trace_id IS NULL OR trace_id ~ '^[0-9a-f]{16,64}$')
);

CREATE INDEX idx_meeting_result_access_tenant_accessed
    ON meeting_intelligence_result_access_audit(tenant_id, accessed_at DESC);
CREATE INDEX idx_meeting_result_access_meeting_accessed
    ON meeting_intelligence_result_access_audit(meeting_id, accessed_at DESC);
CREATE INDEX idx_meeting_result_access_retention
    ON meeting_intelligence_result_access_audit(accessed_at, id);

COMMENT ON TABLE meeting_intelligence_result_access_audit IS
    'KVKK m.12 metadata-only audit for successful canonical Meeting Intelligence result reads. Never stores summary, transcript, decision, action, citation, source, prompt, request, response, or JSON payload content.';
COMMENT ON COLUMN meeting_intelligence_result_access_audit.accessor_subject IS
    'Authenticated context subject; never supplied by a request body.';
COMMENT ON COLUMN meeting_intelligence_result_access_audit.trace_id IS
    'Optional lowercase hex trace id after strict application allowlisting; malformed input is discarded.';

-- The V2 destruction ledger originally covered only action and decision rows.
-- Extend its count invariant before the result-access retention worker writes
-- a separate metadata-only layer row.
ALTER TABLE meeting_retention_destruction_audit
    ADD COLUMN result_access_audit_deleted_count BIGINT NOT NULL DEFAULT 0;

ALTER TABLE meeting_retention_destruction_audit
    DROP CONSTRAINT meeting_retention_audit_counts_ck;

ALTER TABLE meeting_retention_destruction_audit
    ADD CONSTRAINT meeting_retention_audit_counts_ck
        CHECK (
            deleted_count >= 0
            AND action_deleted_count >= 0
            AND decision_deleted_count >= 0
            AND result_access_audit_deleted_count >= 0
            AND deleted_count = action_deleted_count
                + decision_deleted_count
                + result_access_audit_deleted_count
        );

COMMENT ON COLUMN meeting_retention_destruction_audit.result_access_audit_deleted_count IS
    'Count of expired metadata-only Meeting Intelligence result-access audit rows deleted by the independently configured retention layer.';

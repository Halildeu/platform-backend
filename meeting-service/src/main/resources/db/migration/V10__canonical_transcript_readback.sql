-- Durable, content-free evidence used to distinguish exact analysis-run
-- erasure from retention after the result payload itself has been destroyed.
CREATE TABLE meeting_analysis_run_destruction_tombstone (
    analysis_run_id UUID         NOT NULL PRIMARY KEY,
    tenant_id       UUID         NOT NULL,
    org_id          UUID         NOT NULL,
    meeting_id      UUID         NOT NULL,
    session_id      UUID,
    reason          VARCHAR(16)  NOT NULL,
    destroyed_at    TIMESTAMPTZ  NOT NULL,

    CONSTRAINT meeting_analysis_run_destruction_org_match_ck
        CHECK (org_id = tenant_id),
    CONSTRAINT meeting_analysis_run_destruction_reason_ck
        CHECK (reason IN ('ERASURE', 'RETENTION'))
);

CREATE INDEX idx_meeting_analysis_run_destruction_scope
    ON meeting_analysis_run_destruction_tombstone(tenant_id, meeting_id, analysis_run_id);

COMMENT ON TABLE meeting_analysis_run_destruction_tombstone IS
    'Permanent metadata-only evidence for exact analysis-run destruction. Never stores transcript, summary, segment, citation, prompt, request, response, or external source aliases.';

ALTER TABLE meeting_intelligence_result_access_audit
    DROP CONSTRAINT meeting_intelligence_result_access_type_ck;

ALTER TABLE meeting_intelligence_result_access_audit
    ADD CONSTRAINT meeting_intelligence_result_access_type_ck
        CHECK (access_type IN ('CANONICAL_RESULT_READ', 'CANONICAL_TRANSCRIPT_READ'));

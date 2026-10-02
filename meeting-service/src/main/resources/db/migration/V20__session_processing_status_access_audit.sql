-- Session-status reads can precede any analysis run. Keep their identity explicit
-- while preserving the old result-read constraints and existing audit retention.
ALTER TABLE meeting_intelligence_result_access_audit
    ADD COLUMN session_id UUID,
    ALTER COLUMN analysis_run_id DROP NOT NULL;

ALTER TABLE meeting_intelligence_result_access_audit
    DROP CONSTRAINT meeting_intelligence_result_access_type_ck;

ALTER TABLE meeting_intelligence_result_access_audit
    ADD CONSTRAINT meeting_intelligence_result_access_type_ck CHECK (
        (access_type IN ('CANONICAL_RESULT_READ', 'CANONICAL_TRANSCRIPT_READ')
            AND analysis_run_id IS NOT NULL AND session_id IS NULL)
        OR (access_type = 'SESSION_PROCESSING_STATUS_READ'
            AND session_id IS NOT NULL AND analysis_run_id IS NULL)
    );

COMMENT ON COLUMN meeting_intelligence_result_access_audit.session_id IS
    'Exact canonical session for metadata-only status observations, including absent results; no fabricated analysis-run identity.';

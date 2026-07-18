-- #869: backward-compatible canonical finalization occurrence and job capability evidence.
ALTER TABLE meeting_analysis_runs
    ADD COLUMN finalization_version BIGINT,
    ADD COLUMN finalized_at TIMESTAMPTZ,
    ADD COLUMN analysis_spec_version VARCHAR(64),
    ADD COLUMN job_capability_id UUID,
    ADD COLUMN legal_hold BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE meeting_analysis_runs
    ADD CONSTRAINT meeting_analysis_runs_canonical_tuple_coherent CHECK (
        (finalization_version IS NULL AND finalized_at IS NULL
            AND analysis_spec_version IS NULL AND job_capability_id IS NULL)
        OR
        (finalization_version IS NOT NULL AND finalization_version >= 1
            AND finalized_at IS NOT NULL
            AND analysis_spec_version IS NOT NULL AND length(trim(analysis_spec_version)) > 0
            AND job_capability_id IS NOT NULL)
    );

CREATE UNIQUE INDEX uq_meeting_analysis_runs_job_capability
    ON meeting_analysis_runs(job_capability_id)
    WHERE job_capability_id IS NOT NULL;

CREATE INDEX idx_meeting_analysis_runs_canonical_latest
    ON meeting_analysis_runs(
        tenant_id, meeting_id, finalized_at DESC, finalization_version DESC, created_at DESC)
    WHERE finalized_at IS NOT NULL;

CREATE INDEX idx_meeting_analysis_runs_retention
    ON meeting_analysis_runs(created_at, analysis_run_id)
    WHERE legal_hold = FALSE;

COMMENT ON COLUMN meeting_analysis_runs.job_capability_id IS
    'Metadata-only jti of the verified short-lived capability; the signed token is never persisted.';
COMMENT ON COLUMN meeting_analysis_runs.finalized_at IS
    'Source occurrence time used before finalization_version for visible-latest ordering; nullable for pre-V8 legacy rows.';

CREATE TABLE meeting_analysis_job_capability_uses (
    capability_id UUID PRIMARY KEY,
    analysis_run_id UUID NOT NULL REFERENCES meeting_analysis_runs(analysis_run_id) ON DELETE CASCADE,
    consumed_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_meeting_analysis_capability_uses_run
    ON meeting_analysis_job_capability_uses(analysis_run_id);

COMMENT ON TABLE meeting_analysis_job_capability_uses IS
    'Metadata-only one-time-use ledger; signed capabilities and customer content are never persisted.';

ALTER TABLE meeting_retention_destruction_audit
    ADD COLUMN analysis_run_deleted_count BIGINT NOT NULL DEFAULT 0;

ALTER TABLE meeting_retention_destruction_audit
    DROP CONSTRAINT meeting_retention_audit_counts_ck;

ALTER TABLE meeting_retention_destruction_audit
    ADD CONSTRAINT meeting_retention_audit_counts_ck CHECK (
        deleted_count >= 0
        AND action_deleted_count >= 0
        AND decision_deleted_count >= 0
        AND result_access_audit_deleted_count >= 0
        AND analysis_run_deleted_count >= 0
        AND deleted_count = action_deleted_count
            + decision_deleted_count
            + result_access_audit_deleted_count
            + analysis_run_deleted_count
    );

COMMENT ON COLUMN meeting_retention_destruction_audit.analysis_run_deleted_count IS
    'Metadata-only number of analysis-run parents destroyed; child content is deleted by cascade and never copied here.';

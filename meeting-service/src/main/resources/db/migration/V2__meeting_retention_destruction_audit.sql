-- V2 — Faz 24 #156 meeting-intelligence retention cleanup audit.
--
-- Destruction audit is metadata-only: no action description, decision title,
-- decision detail, participant, assignee, or subject values are persisted here.
-- The scheduled cleanup job writes this row in the same transaction as bounded
-- id-only deletes of expired meeting_actions / meeting_decisions rows.

CREATE TABLE meeting_retention_destruction_audit (
    id                       UUID         NOT NULL PRIMARY KEY,
    layer_id                 VARCHAR(96)  NOT NULL,
    cutoff_at                TIMESTAMPTZ  NOT NULL,
    deleted_count            BIGINT       NOT NULL,
    action_deleted_count     BIGINT       NOT NULL,
    decision_deleted_count   BIGINT       NOT NULL,
    job_id                   VARCHAR(96)  NOT NULL,
    audit_payload            VARCHAR(32)  NOT NULL DEFAULT 'metadata-only',
    executed_at              TIMESTAMPTZ  NOT NULL,
    CONSTRAINT meeting_retention_audit_payload_ck
        CHECK (audit_payload IN ('metadata-only', 'id-only', 'transcript-free')),
    CONSTRAINT meeting_retention_audit_counts_ck
        CHECK (
            deleted_count >= 0
            AND action_deleted_count >= 0
            AND decision_deleted_count >= 0
            AND deleted_count = action_deleted_count + decision_deleted_count
        )
);

CREATE INDEX idx_meeting_retention_audit_layer_executed
    ON meeting_retention_destruction_audit(layer_id, executed_at);
CREATE INDEX idx_meeting_retention_audit_job_id
    ON meeting_retention_destruction_audit(job_id);
CREATE INDEX idx_meeting_actions_created_at
    ON meeting_actions(created_at);
CREATE INDEX idx_meeting_decisions_created_at
    ON meeting_decisions(created_at);

COMMENT ON TABLE meeting_retention_destruction_audit IS
    'Faz 24 #156 KVKK m.7 metadata-only destruction audit for meeting-intelligence retention cleanup. No action/decision content or participant identifiers.';
COMMENT ON COLUMN meeting_retention_destruction_audit.audit_payload IS
    'Must remain metadata-only/id-only/transcript-free; never stores action, decision, summary, participant, prompt, response, or transcript text.';

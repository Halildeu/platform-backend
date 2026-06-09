-- V60 — Faz 22.5 #527 slice-1: failed-device rollout queue FOUNDATION.
--
-- BOUNDARY (contract docs/contracts/faz-22-failed-device-queue/; Codex 019eaaf3
-- plan-time AGREE): this slice adds the queue aggregate + append-only event
-- ledger ONLY. It does NOT add ingest/classifier (slice-2), the stop-line
-- threshold evaluator (slice-3), or the GitHub escalation generator (slice-4).
-- No production WRITE REST in slice-1 — rows are seeded by a test fixture that
-- goes through the same schema validator. The contract enforcement flags
-- (live_ingest / threshold_evaluator / github_escalation_generator) stay false.
--
-- MODEL (mirrors the merged JSON schema $defs):
--   endpoint_rollout_failure        — queue aggregate; one ACTIVE row per
--                                     (org_id, rollout_id, wave_id, device_id)
--                                     via a partial unique index. Mutable
--                                     (@Version optimistic lock). evidence is a
--                                     per-class allowlisted redacted JSONB object.
--   endpoint_rollout_failure_event  — append-only immutable audit ledger; one
--                                     row per detection / transition. A
--                                     BEFORE UPDATE OR DELETE trigger rejects.
--
-- ORG CONTRACT (mirrors V58 / V52 / V47): tenant_id for read paths + org_id for
-- org-composite scope. endpoint_org_id_compat_fill() fills org_id=tenant_id;
-- CHECKs validate match + NOT NULL. The DB CHECKs are the durable backstop;
-- the per-class evidence shape + the transition matrix are enforced fail-closed
-- at the service layer (the contract JSON schema, loaded by
-- FailedDeviceQueueSchemaValidator).

-- ----------------------------------------------------------------------------
-- Queue aggregate (mutable, one active per device per wave)
-- ----------------------------------------------------------------------------
CREATE TABLE endpoint_rollout_failure (
    id                          UUID            NOT NULL,
    tenant_id                   UUID            NOT NULL,
    org_id                      UUID,
    rollout_id                  VARCHAR(128)    NOT NULL,
    wave_id                     VARCHAR(128)    NOT NULL,
    device_id                   UUID            NOT NULL,

    current_class               VARCHAR(32)     NOT NULL,
    current_state               VARCHAR(16)     NOT NULL,
    retry_count                 INTEGER         NOT NULL DEFAULT 0,
    max_retries                 INTEGER         NOT NULL DEFAULT 0,

    first_detected_at           TIMESTAMPTZ     NOT NULL,
    last_observed_at            TIMESTAMPTZ     NOT NULL,
    last_transition_at          TIMESTAMPTZ     NOT NULL,

    evidence_redacted_json      JSONB           NOT NULL DEFAULT '{}'::jsonb,
    owner_role                  VARCHAR(128)    NOT NULL,
    stop_line_contribution      BOOLEAN,

    escalation_issue_url        VARCHAR(512),
    waiver_reason               VARCHAR(512),
    waived_by                   VARCHAR(256),
    waived_until                TIMESTAMPTZ,
    resolved_at                 TIMESTAMPTZ,
    resolution_summary          VARCHAR(1024),

    classification_confidence   VARCHAR(8)      NOT NULL,
    classifier_version          VARCHAR(64),

    version                     BIGINT          NOT NULL DEFAULT 0,
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT pk_endpoint_rollout_failure PRIMARY KEY (id),
    CONSTRAINT chk_erf_class
        CHECK (current_class IN ('DNS_EDGE_MTLS','CERT_IDENTITY','INSTALLER_MSI','SERVICE_HMAC_MODE','BACKEND_RESULT_SUBMIT','EDR_NETWORK')),
    CONSTRAINT chk_erf_state
        CHECK (current_state IN ('new','retrying','quarantined','escalated','resolved','waived')),
    CONSTRAINT chk_erf_confidence
        CHECK (classification_confidence IN ('high','medium','low')),
    CONSTRAINT chk_erf_retry CHECK (retry_count >= 0 AND max_retries >= 0),
    CONSTRAINT chk_erf_evidence_object CHECK (jsonb_typeof(evidence_redacted_json) = 'object'),
    CONSTRAINT chk_erf_org_match CHECK (org_id IS NULL OR org_id = tenant_id),
    CONSTRAINT chk_erf_org_not_null CHECK (org_id IS NOT NULL)
);

CREATE TRIGGER endpoint_rollout_failure_org_id_compat BEFORE INSERT OR UPDATE ON endpoint_rollout_failure
    FOR EACH ROW EXECUTE FUNCTION endpoint_org_id_compat_fill();

-- One ACTIVE aggregate per device per wave (resolved/waived history stays queryable).
CREATE UNIQUE INDEX ux_erf_active_device
    ON endpoint_rollout_failure (org_id, rollout_id, wave_id, device_id)
    WHERE current_state IN ('new','retrying','quarantined','escalated');

CREATE INDEX idx_erf_wave_state ON endpoint_rollout_failure (org_id, rollout_id, wave_id, current_state);
CREATE INDEX idx_erf_org_device ON endpoint_rollout_failure (org_id, device_id);

-- ----------------------------------------------------------------------------
-- Append-only event ledger
-- ----------------------------------------------------------------------------
CREATE TABLE endpoint_rollout_failure_event (
    id                          UUID            NOT NULL,
    tenant_id                   UUID            NOT NULL,
    org_id                      UUID,
    failure_id                  UUID            NOT NULL,

    event_type                  VARCHAR(16)     NOT NULL,
    from_state                  VARCHAR(16),
    to_state                    VARCHAR(16)     NOT NULL,
    failure_class               VARCHAR(32)     NOT NULL,
    source_signal               VARCHAR(256),
    redacted_evidence_json      JSONB           NOT NULL DEFAULT '{}'::jsonb,
    actor_type                  VARCHAR(16)     NOT NULL,
    actor_subject_hash          VARCHAR(64),
    classification_confidence   VARCHAR(8),

    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT pk_endpoint_rollout_failure_event PRIMARY KEY (id),
    CONSTRAINT fk_erfe_failure FOREIGN KEY (failure_id) REFERENCES endpoint_rollout_failure (id) ON DELETE CASCADE,
    CONSTRAINT chk_erfe_event_type
        CHECK (event_type IN ('detected','transition','retry','reclassified','escalated','waived','reopened','resolved')),
    CONSTRAINT chk_erfe_from_state
        CHECK (from_state IS NULL OR from_state IN ('new','retrying','quarantined','escalated','resolved','waived')),
    CONSTRAINT chk_erfe_to_state
        CHECK (to_state IN ('new','retrying','quarantined','escalated','resolved','waived')),
    CONSTRAINT chk_erfe_class
        CHECK (failure_class IN ('DNS_EDGE_MTLS','CERT_IDENTITY','INSTALLER_MSI','SERVICE_HMAC_MODE','BACKEND_RESULT_SUBMIT','EDR_NETWORK')),
    CONSTRAINT chk_erfe_actor CHECK (actor_type IN ('auto','operator','system')),
    CONSTRAINT chk_erfe_confidence
        CHECK (classification_confidence IS NULL OR classification_confidence IN ('high','medium','low')),
    CONSTRAINT chk_erfe_evidence_object CHECK (jsonb_typeof(redacted_evidence_json) = 'object'),
    -- detected = the initial event: must land in 'new' with no from_state
    -- (mirrors the contract schema event transition matrix; Codex 019eaac8).
    CONSTRAINT chk_erfe_detected_initial
        CHECK (event_type <> 'detected' OR (from_state IS NULL AND to_state = 'new')),
    CONSTRAINT chk_erfe_nondetected_from
        CHECK (event_type = 'detected' OR from_state IS NOT NULL),
    CONSTRAINT chk_erfe_org_match CHECK (org_id IS NULL OR org_id = tenant_id),
    CONSTRAINT chk_erfe_org_not_null CHECK (org_id IS NOT NULL)
);

CREATE TRIGGER endpoint_rollout_failure_event_org_id_compat BEFORE INSERT OR UPDATE ON endpoint_rollout_failure_event
    FOR EACH ROW EXECUTE FUNCTION endpoint_org_id_compat_fill();

CREATE INDEX idx_erfe_failure_created ON endpoint_rollout_failure_event (failure_id, created_at);

-- Append-only guard: the ledger is immutable once written.
CREATE OR REPLACE FUNCTION endpoint_rollout_failure_event_appendonly() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'endpoint_rollout_failure_event is append-only (% rejected)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_erfe_appendonly BEFORE UPDATE OR DELETE ON endpoint_rollout_failure_event
    FOR EACH ROW EXECUTE FUNCTION endpoint_rollout_failure_event_appendonly();

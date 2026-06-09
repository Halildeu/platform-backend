-- V60 — Faz 22.5 #527 slice-1a: Failed-device queue (tables + read + manual seed).
--
-- BOUNDARY (Codex 019eaaf0 plan-time AGREE-with-refinements; contract
-- docs/contracts/faz-22-failed-device-queue/, gate #520, design 019eaac8):
-- This slice lands ONLY the system-of-record tables + their durable DB
-- invariants. It does NOT add the auto ingest/classifier (§9.2), the stop-line
-- threshold evaluator (§9.3), the GitHub escalation generator (§9.4), the wave
-- export surface, or any transition endpoint. The only write path is a
-- server-derived MANUAL operator seed (initial `new` observation). The
-- enforcement flags threshold_evaluator + github_escalation_generator stay
-- false until §9.3/§9.4 land. This PR therefore does NOT close contract §9.1
-- ("tables + read/EXPORT REST") — export is tracked separately.
--
-- MODEL:
--   endpoint_rollout_failure        — one ACTIVE aggregate per device per wave
--                                     (partial unique on active states); resolved
--                                     /waived rows remain queryable as history.
--   endpoint_rollout_failure_event  — append-only audit ledger; one row per
--                                     transition (UPDATE/DELETE rejected by trigger).
--
-- ORG CONTRACT (Faz 21.1 canonical): org_id NOT NULL, org_id = tenant_id (match
-- CHECK), org-composite FK device_id→endpoint_devices(id, org_id). New tables so
-- org_id is set at insert (no backfill compat-fill needed).
--
-- REDACTION (§7, hard): evidence_redacted_json is a per-class allowlisted
-- redacted object — enforced fail-closed at the SERVICE layer by a code-defined
-- per-class allowlist registry (RolloutFailureEvidenceValidator, pinned to the
-- contract schema by a drift test). The DB CHECK here is the
-- durable backstop: it only asserts the payload is a JSON object (not a scalar/
-- array/raw string), never trusts free text.

-- ----------------------------------------------------------------------------
-- Aggregate — active system-of-record
-- ----------------------------------------------------------------------------
CREATE TABLE endpoint_rollout_failure (
    id                        UUID         NOT NULL,
    tenant_id                 UUID         NOT NULL,
    org_id                    UUID         NOT NULL,
    rollout_id                VARCHAR(128) NOT NULL,
    wave_id                   VARCHAR(128) NOT NULL,
    device_id                 UUID         NOT NULL,

    current_class             VARCHAR(32)  NOT NULL,
    current_state             VARCHAR(16)  NOT NULL,
    retry_count               INTEGER      NOT NULL DEFAULT 0,
    max_retries               INTEGER      NOT NULL,
    classification_confidence VARCHAR(8)   NOT NULL,
    classifier_version        VARCHAR(64)  NOT NULL,
    owner_role                VARCHAR(64),

    -- per-class allowlisted redacted evidence (service-validated typed DTO)
    evidence_redacted_json    JSONB        NOT NULL,

    -- workflow metadata (written by deferred slices; NULL in slice-1a)
    escalation_issue_url      VARCHAR(512),
    waiver_reason             VARCHAR(512),
    waived_by                 VARCHAR(255),
    waived_until              TIMESTAMPTZ,
    resolved_at               TIMESTAMPTZ,
    resolution_summary        VARCHAR(512),

    first_detected_at         TIMESTAMPTZ  NOT NULL,
    last_observed_at          TIMESTAMPTZ  NOT NULL,
    last_transition_at        TIMESTAMPTZ  NOT NULL,
    version                   BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT pk_endpoint_rollout_failure PRIMARY KEY (id),
    CONSTRAINT endpoint_rollout_failure_id_org_id_key UNIQUE (id, org_id),
    CONSTRAINT ck_erf_class CHECK (current_class IN
        ('DNS_EDGE_MTLS','CERT_IDENTITY','INSTALLER_MSI','SERVICE_HMAC_MODE','BACKEND_RESULT_SUBMIT','EDR_NETWORK')),
    CONSTRAINT ck_erf_state CHECK (current_state IN
        ('new','retrying','quarantined','escalated','resolved','waived')),
    CONSTRAINT ck_erf_confidence CHECK (classification_confidence IN ('high','medium','low')),
    CONSTRAINT ck_erf_retry CHECK (retry_count >= 0 AND max_retries >= 0),
    CONSTRAINT ck_erf_evidence_object CHECK (jsonb_typeof(evidence_redacted_json) = 'object'),
    CONSTRAINT ck_erf_org_match CHECK (org_id = tenant_id),
    -- Org-canonical composite device FK (Faz 21.1; mirrors V37/V45/V48/V56/V58).
    CONSTRAINT fk_erf_device FOREIGN KEY (device_id, org_id)
        REFERENCES endpoint_devices (id, org_id)
);

-- One ACTIVE aggregate per (org_id, rollout_id, wave_id, device_id). Resolved/
-- waived rows fall out of the predicate and stay as queryable history; a fresh
-- observation after resolution inserts a NEW active aggregate (never resurrects).
CREATE UNIQUE INDEX uq_endpoint_rollout_failure_active
    ON endpoint_rollout_failure (org_id, rollout_id, wave_id, device_id)
    WHERE current_state IN ('new','retrying','quarantined','escalated');

CREATE INDEX ix_erf_wave ON endpoint_rollout_failure (org_id, rollout_id, wave_id);
CREATE INDEX ix_erf_device ON endpoint_rollout_failure (org_id, device_id);

-- ----------------------------------------------------------------------------
-- Append-only event ledger
-- ----------------------------------------------------------------------------
CREATE TABLE endpoint_rollout_failure_event (
    id                        UUID         NOT NULL,
    tenant_id                 UUID         NOT NULL,
    org_id                    UUID         NOT NULL,
    failure_id                UUID         NOT NULL,

    event_type                VARCHAR(32)  NOT NULL,
    from_state                VARCHAR(16),
    to_state                  VARCHAR(16)  NOT NULL,
    class                     VARCHAR(32)  NOT NULL,
    source_signal             VARCHAR(64)  NOT NULL,
    redacted_evidence_json    JSONB        NOT NULL,
    actor_type                VARCHAR(16)  NOT NULL,
    actor_subject_hash        VARCHAR(64),
    classification_confidence VARCHAR(8)   NOT NULL,
    created_at                TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_endpoint_rollout_failure_event PRIMARY KEY (id),
    CONSTRAINT ck_erfe_to_state CHECK (to_state IN
        ('new','retrying','quarantined','escalated','resolved','waived')),
    CONSTRAINT ck_erfe_from_state CHECK (from_state IS NULL OR from_state IN
        ('new','retrying','quarantined','escalated','resolved','waived')),
    CONSTRAINT ck_erfe_class CHECK (class IN
        ('DNS_EDGE_MTLS','CERT_IDENTITY','INSTALLER_MSI','SERVICE_HMAC_MODE','BACKEND_RESULT_SUBMIT','EDR_NETWORK')),
    -- Contract event/actor enums (failed-device-queue.schema.json $defs.event).
    CONSTRAINT ck_erfe_event_type CHECK (event_type IN
        ('detected','transition','retry','reclassified','escalated','waived','reopened','resolved')),
    CONSTRAINT ck_erfe_actor_type CHECK (actor_type IN ('auto','operator','system')),
    CONSTRAINT ck_erfe_confidence CHECK (classification_confidence IN ('high','medium','low')),
    CONSTRAINT ck_erfe_actor_subject_hash CHECK (actor_subject_hash IS NULL OR actor_subject_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_erfe_evidence_object CHECK (jsonb_typeof(redacted_evidence_json) = 'object'),
    CONSTRAINT ck_erfe_org_match CHECK (org_id = tenant_id),
    CONSTRAINT fk_erfe_failure FOREIGN KEY (failure_id, org_id)
        REFERENCES endpoint_rollout_failure (id, org_id)
);

-- Deterministic ledger ordering even on equal created_at.
CREATE INDEX ix_erfe_failure ON endpoint_rollout_failure_event (failure_id, created_at, id);

-- Append-only: reject any UPDATE/DELETE at the DB level (audit integrity).
CREATE OR REPLACE FUNCTION endpoint_rollout_failure_event_append_only()
    RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'endpoint_rollout_failure_event is append-only (% rejected)', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_erfe_append_only
    BEFORE UPDATE OR DELETE ON endpoint_rollout_failure_event
    FOR EACH ROW EXECUTE FUNCTION endpoint_rollout_failure_event_append_only();

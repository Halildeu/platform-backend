-- Faz 22.5 #527 §9.3: orchestrator-owned wave/fleet metrics snapshot.
--
-- The stop-line threshold evaluator (contract §6) needs denominators —
-- active_wave_size + fleet_size + the rollout-scoped stale_24h_count — that the
-- backend CANNOT derive (there is no rollout/wave/fleet membership in this
-- service; endpoint_devices has only a deployment_ring + last_seen_at, no
-- rollout_id/wave_id). The authoritative source is the external deployment
-- orchestrator that DEFINES the 50..800 rollout waves/fleet. It writes a
-- persisted, provenance-bearing snapshot here; the backend evaluates §6's
-- formulas against the LATEST snapshot and emits a COMPUTED, ADVISORY
-- stop_line_status (enforced=false — §6 enforcement stays deferred; this never
-- gates a deployment). Append-only history (audit + reproducibility).

CREATE TABLE endpoint_rollout_wave_metrics_snapshot (
    id                  UUID            NOT NULL,
    tenant_id           UUID            NOT NULL,
    org_id              UUID            NOT NULL,
    rollout_id          VARCHAR(128)    NOT NULL,
    wave_id             VARCHAR(128)    NOT NULL,
    -- Orchestrator-supplied, rollout-scoped metrics. Denominators must be > 0
    -- (an undefined percentage must never surface as available=true).
    active_wave_size    INTEGER         NOT NULL,
    fleet_size          INTEGER         NOT NULL,
    stale_24h_count     INTEGER         NOT NULL,
    -- Provenance: source_type is a server-fixed constant (the principal proves
    -- "who"); source_snapshot_id is an optional bounded, non-PII orchestrator ref.
    source_type         VARCHAR(64)     NOT NULL,
    source_snapshot_id  VARCHAR(128),
    captured_at         TIMESTAMPTZ     NOT NULL,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT pk_erwms PRIMARY KEY (id),
    CONSTRAINT ck_erwms_org_match CHECK (org_id = tenant_id),
    CONSTRAINT ck_erwms_active_wave_size CHECK (active_wave_size > 0),
    CONSTRAINT ck_erwms_fleet_size CHECK (fleet_size > 0),
    CONSTRAINT ck_erwms_stale_nonneg CHECK (stale_24h_count >= 0),
    CONSTRAINT ck_erwms_fleet_ge_wave CHECK (fleet_size >= active_wave_size),
    CONSTRAINT ck_erwms_stale_le_fleet CHECK (stale_24h_count <= fleet_size),
    CONSTRAINT ck_erwms_source_type CHECK (source_type = 'orchestrator_snapshot')
);

-- Deterministic "latest per (org, rollout, wave)" — captured_at, then created_at,
-- then id as the final tie-break.
CREATE INDEX ix_erwms_latest
    ON endpoint_rollout_wave_metrics_snapshot
        (tenant_id, rollout_id, wave_id, captured_at DESC, created_at DESC, id DESC);

-- Append-only history: a snapshot is an immutable point-in-time record.
CREATE OR REPLACE FUNCTION trg_erwms_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'endpoint_rollout_wave_metrics_snapshot is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_erwms_append_only
    BEFORE UPDATE OR DELETE ON endpoint_rollout_wave_metrics_snapshot
    FOR EACH ROW EXECUTE FUNCTION trg_erwms_append_only();

-- Faz 22.8A.3b (#648) — backup dry-run issuing request (propose→approve
-- dual-control). Consumes the 22.8A.3a managed-root registry by OPAQUE
-- root_ref; the request/audit/response stay path-free (raw local_path is
-- resolved only into the dispatch command payload, which the generic command
-- DTO redacts). Codex 019ec45e merge-blockers.
--
-- State machine:  PENDING_APPROVAL → APPROVED   (maker-checker: proposer ≠ approver)
--
-- DURABLE invariants:
--   * byod IS FALSE CHECK — BYOD fail-closed this slice (relaxed by a later
--     migration if a BYOD/consent slice lands).
--   * roots_snapshot JSONB = [{root_ref, root_version}] captured at propose;
--     approve revalidates each root's CURRENT registry root_version against the
--     snapshot (drift → 409/422 re-propose, never silent re-resolve). Path-free
--     (opaque root_ref + integer version only).
--   * single-flight: at most ONE PENDING_APPROVAL request per (org_id, device).
--   * idempotency: unique (org_id, idempotency_key).
--
-- ORG CONTRACT (mirrors V58/V60/V69): tenant_id read path + org_id composite;
-- endpoint_org_id_compat_fill() fills org_id=tenant_id; match/not-null CHECK.
--
-- Disabled-by-default consumer (endpoint-admin.backup-dryrun.enabled=false +
-- per-tenant opt-in).

CREATE TABLE endpoint_backup_dryrun_requests (
    id                   UUID         NOT NULL,
    tenant_id            UUID         NOT NULL,
    org_id               UUID,
    device_id            UUID         NOT NULL,
    command_id           UUID,
    idempotency_key      VARCHAR(255) NOT NULL,
    state                VARCHAR(32)  NOT NULL,
    allowlist_profile_id VARCHAR(255) NOT NULL,
    byod                 BOOLEAN      NOT NULL DEFAULT FALSE,
    reason               VARCHAR(512),
    -- [{ "root_ref": "managed_root:...", "root_version": 1 }] — path-free.
    roots_snapshot       JSONB        NOT NULL,
    created_by           VARCHAR(255) NOT NULL,
    approved_by          VARCHAR(255),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    state_updated_at     TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_bdr_requests PRIMARY KEY (id),
    CONSTRAINT uq_bdr_req_id_org UNIQUE (id, org_id),
    CONSTRAINT ck_bdr_req_state CHECK (state IN ('PENDING_APPROVAL', 'APPROVED')),
    CONSTRAINT ck_bdr_req_byod_false CHECK (byod IS FALSE),
    CONSTRAINT ck_bdr_req_allowlist_profile_opaque
        CHECK (allowlist_profile_id ~ '^[A-Za-z0-9._:-]+$'),
    CONSTRAINT ck_bdr_req_org_match CHECK (org_id IS NULL OR org_id = tenant_id),
    CONSTRAINT ck_bdr_req_org_not_null CHECK (org_id IS NOT NULL)
);

CREATE TRIGGER endpoint_backup_dryrun_requests_org_id_compat
    BEFORE INSERT OR UPDATE ON endpoint_backup_dryrun_requests
    FOR EACH ROW EXECUTE FUNCTION endpoint_org_id_compat_fill();

CREATE UNIQUE INDEX uq_bdr_req_idempotency
    ON endpoint_backup_dryrun_requests (org_id, idempotency_key);

-- single-flight: one open (PENDING_APPROVAL) request per device.
CREATE UNIQUE INDEX uq_bdr_req_one_pending
    ON endpoint_backup_dryrun_requests (org_id, device_id)
    WHERE state = 'PENDING_APPROVAL';

CREATE INDEX ix_bdr_req_device_created
    ON endpoint_backup_dryrun_requests (tenant_id, device_id, created_at DESC);

COMMENT ON TABLE endpoint_backup_dryrun_requests IS
    'Faz 22.8A.3b #648: backup dry-run issuing request (propose→approve dual-control). '
    'roots_snapshot is path-free [{root_ref,root_version}]; raw local_path lives only in the '
    'registry + the redacted dispatch payload. byod IS FALSE (fail-closed); single-flight per device.';

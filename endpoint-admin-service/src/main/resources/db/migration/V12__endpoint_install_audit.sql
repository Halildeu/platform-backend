-- BE-021 — Install Audit + Detection State (Faz 22.5). Codex thread
-- 019e6dfb iter-3 AGREE (ready_for_impl=true). Builds on V2 (commands /
-- command_results / audit_events), V7 (catalog), V8 (inventory) and V10
-- (compliance state) without breaking any existing contract.
--
-- This migration delivers four DDL changes:
--
--   1. Extend the endpoint_commands.command_type CHECK constraint with
--      the new INSTALL_SOFTWARE value. The generic admin command
--      controller (AdminEndpointCommandController) does not accept this
--      type — the dedicated POST .../installs path is the only legal
--      surface (Codex iter-3 P0-1 absorb: prevent preflight bypass).
--
--   2. Add (id, tenant_id) UNIQUE indices on endpoint_commands and
--      endpoint_devices so the new install-audit table can use composite
--      foreign keys to enforce tenant integrity at the DB layer (parity
--      with V10's catalog (id, tenant_id) constraint — Codex iter-3
--      P0-3 absorb).
--
--   3. Create endpoint_install_audit — append-on-terminal-result history
--      of INSTALL_SOFTWARE command outcomes. Composite FKs on (command,
--      device, catalog) all bind the tenant column so a service bug
--      cannot cross tenants even with a misrouted id. UNIQUE(command_id)
--      mirrors endpoint_command_results' uniqueness — one terminal
--      result per command (Codex iter-3 P0-3/F).
--
--   4. Detection telemetry columns (detected_package_id, detected_version,
--      post_verification, post_verification_evidence) carry the
--      backend-redacted install observation. catalog_item_id is the
--      internal UUID (FK target); catalog_package_id is denormalized so
--      audit reads do not require a catalog join just to render the
--      package identifier.
--
-- Migration sequence guard: V11 (BE-022 mTLS) was the last applied
-- migration on origin/main. V12 claims this slot exclusively for BE-021.
-- The V10 catalog (id, tenant_id) UNIQUE is NOT recreated here (already
-- owned by V10).

-- ---------------------------------------------------------------------
-- 1. Extend command_type CHECK with INSTALL_SOFTWARE
--
-- V2 baseline used inline `CONSTRAINT ck_endpoint_commands_type CHECK
-- (...)` syntax but the live cluster's endpoint_admin DB was bootstrapped
-- with the PG-auto-generated constraint name `endpoint_commands_command_type_check`.
-- Dropping by a hard-coded name therefore fails on existing deployments.
-- Discover the actual command_type CHECK constraint by definition (so
-- both naming conventions are handled) and drop it dynamically before
-- re-adding the canonical `ck_endpoint_commands_type`. The new constraint
-- name is stable across re-deployments and future Flyway migrations.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    cn text;
BEGIN
    SELECT conname INTO cn
    FROM pg_constraint
    WHERE conrelid = 'endpoint_commands'::regclass
      AND contype = 'c'
      AND pg_get_constraintdef(oid) LIKE '%command_type%';
    IF cn IS NOT NULL THEN
        EXECUTE 'ALTER TABLE endpoint_commands DROP CONSTRAINT ' || quote_ident(cn);
    END IF;
END $$;

ALTER TABLE endpoint_commands ADD CONSTRAINT ck_endpoint_commands_type
    CHECK (command_type IN (
        'COLLECT_INVENTORY',
        'LOCK_USER_LOGIN',
        'UNLOCK_USER_LOGIN',
        'CHANGE_LOCAL_PASSWORD',
        'SMB_LIST_ALLOWED_PATH',
        'SMB_READ_FILE_METADATA',
        'SMB_DOWNLOAD_FILE',
        'SMB_UPLOAD_FILE',
        'ROTATE_CREDENTIAL',
        'INSTALL_SOFTWARE'
    ));

-- ---------------------------------------------------------------------
-- 2. Composite-FK enablers — commands + devices (catalog already done in V10)
-- ---------------------------------------------------------------------
ALTER TABLE endpoint_commands
    ADD CONSTRAINT uq_endpoint_commands_id_tenant UNIQUE (id, tenant_id);

ALTER TABLE endpoint_devices
    ADD CONSTRAINT uq_endpoint_devices_id_tenant UNIQUE (id, tenant_id);

-- ---------------------------------------------------------------------
-- 3. endpoint_install_audit — append-on-terminal-result history
-- ---------------------------------------------------------------------
CREATE TABLE endpoint_install_audit (
    id                          UUID            NOT NULL,
    tenant_id                   UUID            NOT NULL,
    device_id                   UUID            NOT NULL,
    command_id                  UUID            NOT NULL,
    catalog_item_id             UUID            NOT NULL,
    catalog_package_id          VARCHAR(255)    NOT NULL,
    catalog_row_version         BIGINT          NOT NULL,
    preflight_decision          VARCHAR(16)     NOT NULL,
    preflight_decision_at       TIMESTAMPTZ     NOT NULL,
    preflight_warn_codes        JSONB           NOT NULL DEFAULT '[]'::jsonb,
    actor_subject               VARCHAR(255)    NOT NULL,
    approval_subject            VARCHAR(255),
    result_status               VARCHAR(32)     NOT NULL,
    exit_code                   INTEGER,
    reported_at                 TIMESTAMPTZ     NOT NULL,
    started_at                  TIMESTAMPTZ,
    finished_at                 TIMESTAMPTZ,
    post_verification           VARCHAR(16)     NOT NULL,
    detected_package_id         VARCHAR(255),
    detected_version            VARCHAR(128),
    post_verification_evidence  JSONB           NOT NULL DEFAULT '{}'::jsonb,
    redacted_payload            JSONB           NOT NULL DEFAULT '{}'::jsonb,
    row_version                 BIGINT          NOT NULL DEFAULT 0,
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT pk_endpoint_install_audit PRIMARY KEY (id),

    CONSTRAINT uq_endpoint_install_audit_command UNIQUE (command_id),

    CONSTRAINT ck_endpoint_install_audit_result_status
        CHECK (result_status IN ('SUCCEEDED', 'FAILED', 'PARTIAL', 'UNSUPPORTED')),

    CONSTRAINT ck_endpoint_install_audit_preflight_decision
        CHECK (preflight_decision IN ('PASS', 'WARN')),

    CONSTRAINT ck_endpoint_install_audit_post_verification
        CHECK (post_verification IN ('SATISFIED', 'UNSATISFIED', 'UNKNOWN')),

    CONSTRAINT ck_endpoint_install_audit_post_verification_shape
        CHECK (jsonb_typeof(post_verification_evidence) = 'object'),

    CONSTRAINT ck_endpoint_install_audit_redacted_payload_shape
        CHECK (jsonb_typeof(redacted_payload) = 'object'),

    CONSTRAINT ck_endpoint_install_audit_warn_codes_shape
        CHECK (jsonb_typeof(preflight_warn_codes) = 'array'),

    -- Composite FKs enforce tenant integrity at the DB layer (Codex
    -- iter-3 P0-3 absorb): cross-tenant misrouting is physically
    -- impossible because the FK has to match both columns.
    CONSTRAINT fk_endpoint_install_audit_command
        FOREIGN KEY (command_id, tenant_id)
        REFERENCES endpoint_commands (id, tenant_id) ON DELETE CASCADE,

    CONSTRAINT fk_endpoint_install_audit_device
        FOREIGN KEY (device_id, tenant_id)
        REFERENCES endpoint_devices (id, tenant_id) ON DELETE CASCADE,

    CONSTRAINT fk_endpoint_install_audit_catalog
        FOREIGN KEY (catalog_item_id, tenant_id)
        REFERENCES endpoint_software_catalog_items (id, tenant_id) ON DELETE RESTRICT
);

-- Per-device history lookup (UI install history accordion).
CREATE INDEX idx_endpoint_install_audit_tenant_device_time
    ON endpoint_install_audit (tenant_id, device_id, reported_at DESC);

-- Cross-device catalog rollout view (future BE-024 reporting).
CREATE INDEX idx_endpoint_install_audit_tenant_catalog_time
    ON endpoint_install_audit (tenant_id, catalog_item_id, reported_at DESC);

-- Status filter for failed-installs ops dashboard.
CREATE INDEX idx_endpoint_install_audit_tenant_status_time
    ON endpoint_install_audit (tenant_id, result_status, reported_at DESC);

-- BE-023 compliance evaluator deterministic selector: latest SUCCEEDED +
-- SATISFIED audit per (tenant, device, catalog) before evaluationStartedAt.
-- The selector query filters on result_status='SUCCEEDED' and
-- post_verification='SATISFIED'; including them in the index lets the
-- query use an index-only scan for the latest pointer.
CREATE INDEX idx_endpoint_install_audit_eval_selector
    ON endpoint_install_audit (
        tenant_id,
        device_id,
        catalog_item_id,
        created_at DESC
    )
    WHERE result_status = 'SUCCEEDED'
      AND post_verification = 'SATISFIED';

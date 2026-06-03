-- AG-028 Phase 0 — Catalog uninstall flags + change-request flow (Faz 22.5.6).
--
-- Adds two boolean fields to `endpoint_software_catalog_items`:
--   uninstall_supported  — opt-in gate for AG-028 Managed Uninstall (default FALSE).
--   uninstall_protected  — hard guard for critical/system packages (default FALSE).
--
-- Both flags default FALSE so this migration is safe to deploy ahead of the
-- uninstall surface (V32 + AG-028 Phase 1). No existing catalog row will be
-- uninstallable without explicit operator action through the
-- catalog_uninstall_settings_change_requests propose/approve flow.
--
-- Approved catalog rows are immutable via direct PATCH in the current
-- EndpointSoftwareCatalogService (DRAFT-only PUT path). Flag flips on an
-- APPROVED row MUST go through a propose/approve flow analogous to the
-- DRAFT→APPROVED transition. This migration creates the
-- `catalog_uninstall_settings_change_requests` table with maker-checker DB
-- CHECK invariants mirroring the V7 catalog approval pattern.
--
-- Cross-AI plan consensus: Codex MCP thread
-- `019e8c8a-4c90-7c00-8f64-c88d47801a06` 6-iter loop, iter-6 AGREE
-- (2026-06-03). Tracking issue: platform-k8s-gitops #1239.
-- See ../faz-22-software-deployment-plan.md row AG-028 (§22.5.6).
--
-- Slot history: V29 + V30 slots were taken by the parallel Faz 21.1 org_id
-- chain (#391/#392) on 2026-06-03. AG-028 Phase 0 rebases to V31; Phase 1
-- (uninstall surface tables) will claim V32 in a follow-up PR.
--
-- Java field naming note: `protected` is a Java reserved keyword, so the
-- JPA entity uses `uninstallProtected` (Java) <-> `uninstall_protected` (SQL).

-- ─────────────────────────────────────────────────────────────────────
-- 1. Catalog row extension
-- ─────────────────────────────────────────────────────────────────────

ALTER TABLE endpoint_software_catalog_items
    ADD COLUMN uninstall_supported BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN uninstall_protected BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN endpoint_software_catalog_items.uninstall_supported IS
    'AG-028 opt-in gate. Default FALSE. Flip requires catalog_uninstall_settings_change_requests propose/approve maker-checker flow (caller != proposer).';

COMMENT ON COLUMN endpoint_software_catalog_items.uninstall_protected IS
    'AG-028 hard guard for critical/system packages (endpoint-agent, OS components, KB hotfixes). Default FALSE. Flip TRUE simple; flip FALSE requires elevated approver role at service layer.';

-- ─────────────────────────────────────────────────────────────────────
-- 2. Change-request table — propose/approve flow for approved-row flag flips
-- ─────────────────────────────────────────────────────────────────────
--
-- Lifecycle: PROPOSED -> APPROVED -> APPLIED (terminal success)
--                     -> REJECTED                        (terminal reject)
--
-- DB CHECK invariants mirror V7 catalog approval (approved_by != proposed_by,
-- approval pair, terminal state pairing). Service layer enforces additional
-- guards (elevated approver for uninstall_protected unprotect, RBAC, etc.).

CREATE TABLE catalog_uninstall_settings_change_requests (
    id              UUID            NOT NULL,
    tenant_id       UUID            NOT NULL,
    catalog_item_id UUID            NOT NULL,
    field           VARCHAR(32)     NOT NULL,
    new_value       BOOLEAN         NOT NULL,
    proposed_by     VARCHAR(255)    NOT NULL,
    proposed_at     TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    approved_by     VARCHAR(255),
    approved_at     TIMESTAMPTZ,
    applied_at      TIMESTAMPTZ,
    state           VARCHAR(16)     NOT NULL,
    reject_reason   TEXT,
    reason          TEXT,
    version         BIGINT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_catalog_uninstall_settings_change_requests PRIMARY KEY (id),

    -- Composite tenant FK parity (V10/V12 pattern): catalog row must exist
    -- and tenant must match. Prevents cross-tenant misroute at DB layer.
    CONSTRAINT fk_catalog_unins_change_catalog
        FOREIGN KEY (catalog_item_id, tenant_id)
        REFERENCES endpoint_software_catalog_items (id, tenant_id),

    CONSTRAINT ck_catalog_unins_change_field
        CHECK (field IN ('UNINSTALL_SUPPORTED', 'UNINSTALL_PROTECTED')),

    CONSTRAINT ck_catalog_unins_change_state
        CHECK (state IN ('PROPOSED', 'APPROVED', 'APPLIED', 'REJECTED')),

    -- Maker-checker invariant: approver MUST differ from proposer.
    -- Mirrors V7 catalog approval ck_endpoint_software_catalog_items_maker_checker.
    CONSTRAINT ck_catalog_unins_change_maker_checker
        CHECK (approved_by IS NULL OR approved_by <> proposed_by),

    -- Approval pair: approved_by and approved_at always together.
    CONSTRAINT ck_catalog_unins_change_approved_pair
        CHECK ((approved_at IS NULL) = (approved_by IS NULL)),

    -- Terminal state pairing.
    -- PROPOSED: approval pair + applied_at NULL.
    -- APPROVED: approval pair populated, applied_at NULL.
    -- APPLIED:  approval pair + applied_at populated.
    -- REJECTED: reject_reason populated.
    CONSTRAINT ck_catalog_unins_change_state_pairing
        CHECK (
            (state = 'PROPOSED'
                AND approved_by IS NULL
                AND approved_at IS NULL
                AND applied_at IS NULL)
         OR (state = 'APPROVED'
                AND approved_by IS NOT NULL
                AND approved_at IS NOT NULL
                AND applied_at IS NULL)
         OR (state = 'APPLIED'
                AND approved_by IS NOT NULL
                AND approved_at IS NOT NULL
                AND applied_at IS NOT NULL)
         OR (state = 'REJECTED'
                AND approved_by IS NULL
                AND approved_at IS NULL
                AND applied_at IS NULL
                AND reject_reason IS NOT NULL
                AND btrim(reject_reason) <> '')
        )
);

-- One open change-request per (tenant, catalog item, field). Second
-- concurrent propose hits this partial unique index → service maps to 409.
CREATE UNIQUE INDEX uq_catalog_unins_change_one_open
    ON catalog_uninstall_settings_change_requests (tenant_id, catalog_item_id, field)
    WHERE state IN ('PROPOSED', 'APPROVED');

-- Per-tenant list / count for admin UI.
CREATE INDEX idx_catalog_unins_change_tenant_state
    ON catalog_uninstall_settings_change_requests (tenant_id, state);

-- Per-catalog-item history view.
CREATE INDEX idx_catalog_unins_change_catalog_state
    ON catalog_uninstall_settings_change_requests (catalog_item_id, state);

COMMENT ON TABLE catalog_uninstall_settings_change_requests IS
    'AG-028 catalog flag change-request flow. Maker-checker enforced at DB CHECK (approved_by != proposed_by) and service layer (elevated approver for uninstall_protected unprotect). One open request per (tenant, catalog, field) via partial unique index.';

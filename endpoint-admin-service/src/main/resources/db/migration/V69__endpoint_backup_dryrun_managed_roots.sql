-- Faz 22.8A.3a (#648) — managed-data-root registry for the backup dry-run
-- issuing surface (contract §4; Codex 019ec45e "registry-first").
--
-- Privacy: the issuing surface (22.8A.3b) references roots by OPAQUE root_ref
-- only; this table is the ONLY place the raw managed-root local_path lives.
-- The path-free boundary (mirror BackupDryRunManifestPayloadPolicy) extends to
-- the issuing layer: admin request/audit/response carry root_ref, never
-- local_path. root_version supports the propose→approve drift snapshot (the
-- approved scope must equal the dispatched scope).
--
-- Desired-state schema only; the surface that consumes it is disabled-by-default
-- (endpoint-admin.backup-dryrun.enabled=false + per-tenant opt-in).

CREATE TABLE endpoint_backup_dryrun_managed_roots (
    id              UUID PRIMARY KEY,
    tenant_id       UUID         NOT NULL,
    -- opaque registry reference surfaced everywhere (request/audit/manifest);
    -- the backend service additionally validates the token charset
    -- ([A-Za-z0-9._-]+) — this CHECK is the coarse DB guard.
    root_ref        VARCHAR(255) NOT NULL,
    path_class      VARCHAR(64)  NOT NULL,
    -- raw managed-root path: INTERNAL-ONLY. Never returned in a path-free DTO,
    -- never written to an audit event; resolved only into the dispatch payload.
    local_path      TEXT         NOT NULL,
    company_managed BOOLEAN      NOT NULL DEFAULT TRUE,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    -- bumped whenever local_path / path_class / company_managed changes, so a
    -- propose-time snapshot can detect drift at approve time.
    root_version    INTEGER      NOT NULL DEFAULT 1,
    created_by      VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_bdr_managed_root_ref UNIQUE (tenant_id, root_ref),
    CONSTRAINT ck_bdr_managed_root_path_class CHECK (path_class IN (
        'managed/onedrive-business',
        'managed/sharepoint',
        'managed/unc-corp',
        'managed/it-folder',
        'mdm-gpo-root'
    )),
    CONSTRAINT ck_bdr_managed_root_ref_prefix CHECK (root_ref LIKE 'managed\_root:%' ESCAPE '\'),
    CONSTRAINT ck_bdr_managed_root_version_positive CHECK (root_version >= 1)
);

CREATE INDEX ix_bdr_managed_roots_tenant_enabled
    ON endpoint_backup_dryrun_managed_roots (tenant_id, enabled);

COMMENT ON TABLE endpoint_backup_dryrun_managed_roots IS
    'Faz 22.8A.3a #648: managed-data-root registry for the backup dry-run issuing surface. '
    'root_ref opaque (surfaced everywhere); local_path internal-only (never in path-free DTO/audit). '
    'root_version supports the propose->approve drift snapshot. Consumer disabled-by-default.';

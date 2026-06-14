-- Faz 22.8A.3a (#648) — managed-data-root registry for the backup dry-run
-- issuing surface (contract §4; Codex 019ec45e "registry-first" + post-impl
-- hardening 019ec45e round-2).
--
-- Privacy: the issuing surface (22.8A.3b) references roots by OPAQUE root_ref
-- only; this table is the ONLY place the raw managed-root local_path lives.
-- The path-free boundary (mirror BackupDryRunManifestPayloadPolicy) extends to
-- the issuing layer: admin request/audit/response carry root_ref, never
-- local_path. root_version supports the propose→approve drift snapshot.
--
-- DURABLE invariants (Codex 019ec45e round-2 — not just the service layer):
--   * root_ref opaque enforced by a REGEX CHECK (not just a prefix LIKE), so a
--     path-shaped root_ref cannot be direct-inserted/imported and leak via the
--     admin response.
--   * company_managed IS TRUE CHECK — BYOD fail-closed at the DB (a BYOD slice
--     would deliberately relax this via a later migration).
--   * updated_by actor trail for the security-relevant enable/disable action.
--
-- ORG CONTRACT (mirrors V58 / V60 / V52): tenant_id for read paths + org_id for
-- org-composite scope. endpoint_org_id_compat_fill() fills org_id=tenant_id;
-- CHECKs validate match + NOT NULL (durable backstop). Business unique is
-- org-keyed; (id, org_id) is reserved as the org-composite FK target for the
-- 22.8A.3b request table.
--
-- Desired-state schema only; the surface that consumes it is disabled-by-default
-- (endpoint-admin.backup-dryrun.enabled=false + per-tenant opt-in).

CREATE TABLE endpoint_backup_dryrun_managed_roots (
    id              UUID         NOT NULL,
    tenant_id       UUID         NOT NULL,
    org_id          UUID,
    root_ref        VARCHAR(255) NOT NULL,
    path_class      VARCHAR(64)  NOT NULL,
    -- raw managed-root path: INTERNAL-ONLY. Never returned in a path-free DTO,
    -- never written to an audit event; resolved only into the dispatch payload.
    local_path      TEXT         NOT NULL,
    company_managed BOOLEAN      NOT NULL DEFAULT TRUE,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    -- bumped whenever local_path / path_class change, so a propose-time snapshot
    -- can detect drift at approve time.
    root_version    INTEGER      NOT NULL DEFAULT 1,
    created_by      VARCHAR(255) NOT NULL,
    -- actor of the most recent mutation (register or enable/disable) — path-free
    -- operator trail (Codex 019ec45e round-2).
    updated_by      VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_bdr_managed_roots PRIMARY KEY (id),
    CONSTRAINT uq_bdr_managed_root_org_ref UNIQUE (org_id, root_ref),
    CONSTRAINT uq_bdr_managed_root_id_org UNIQUE (id, org_id),
    CONSTRAINT ck_bdr_managed_root_path_class CHECK (path_class IN (
        'managed/onedrive-business',
        'managed/sharepoint',
        'managed/unc-corp',
        'managed/it-folder',
        'mdm-gpo-root'
    )),
    -- opaque token: managed_root:<[A-Za-z0-9._-]+> — durable backstop for the
    -- service-layer positive allowlist (no path-shaped root_ref can persist).
    CONSTRAINT ck_bdr_managed_root_ref_opaque CHECK (root_ref ~ '^managed_root:[A-Za-z0-9._-]+$'),
    -- BYOD fail-closed at the DB (this slice = company-managed roots only).
    CONSTRAINT ck_bdr_managed_root_company_managed CHECK (company_managed IS TRUE),
    CONSTRAINT ck_bdr_managed_root_version_positive CHECK (root_version >= 1),
    CONSTRAINT ck_bdr_managed_root_org_match CHECK (org_id IS NULL OR org_id = tenant_id),
    CONSTRAINT ck_bdr_managed_root_org_not_null CHECK (org_id IS NOT NULL)
);

CREATE TRIGGER endpoint_backup_dryrun_managed_roots_org_id_compat
    BEFORE INSERT OR UPDATE ON endpoint_backup_dryrun_managed_roots
    FOR EACH ROW EXECUTE FUNCTION endpoint_org_id_compat_fill();

CREATE INDEX ix_bdr_managed_roots_tenant_enabled
    ON endpoint_backup_dryrun_managed_roots (tenant_id, enabled);

COMMENT ON TABLE endpoint_backup_dryrun_managed_roots IS
    'Faz 22.8A.3a #648: managed-data-root registry for the backup dry-run issuing surface. '
    'root_ref opaque (regex CHECK; surfaced everywhere); local_path internal-only (never in '
    'path-free DTO/audit); company_managed IS TRUE (BYOD fail-closed); org-keyed (org_id=tenant_id '
    'compat-fill). root_version backs the propose->approve drift snapshot. Consumer disabled-by-default.';

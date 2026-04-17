-- P1.2 follow-up: ensure ADMIN role (role name='ADMIN') has a grant row for every
-- permission in the master catalog. Idempotent: uses WHERE NOT EXISTS, safe to re-run.
-- Motivation: admin@example.com super-admin login path needs /authz/me to return
-- allowedModules covering every module; role_permissions was historically incomplete
-- (missing PURCHASE/WAREHOUSE VIEW + DELETE_PO action). Staging fix 2026-04-17
-- (direct SQL) is now captured as a declarative migration so local dev + prod
-- reach the same baseline on fresh boot.
--
-- permission_type / permission_key / grant_type derivation mirrors
-- RolePermissionGranuleDefaults.resolve() (single source of truth for code→granule):
--   - isManageLike match set: MANAGE / WRITE / CREATE / DELETE / UPDATE / APPROVE /
--     EXPORT / IMPORT / ADMIN / CONFIGURE   (kept in parity with Java helper)
--   - reports.* and dashboards.* → PermissionType.REPORT (ALLOW grant)
--   - Canonical module keys aligned with AccessRoleService.deriveModuleIdentity()
--     and PermissionCatalogService.MODULES entries (PR #440, P1.2).

INSERT INTO role_permissions (role_id, permission_id, permission_type, permission_key, grant_type)
SELECT r.id, p.id,
    CASE
        WHEN p.code LIKE 'reports.%' OR p.code LIKE 'dashboards.%' THEN 'REPORT'
        WHEN p.code IN ('APPROVE_PURCHASE','DELETE_PO','role-manage','permission-manage','permission-scope-manage','system-configure') THEN 'ACTION'
        ELSE 'MODULE'
    END AS permission_type,
    CASE
        WHEN p.code = 'THEME_ADMIN' THEN 'THEME'
        WHEN p.code LIKE 'reports.%' THEN SUBSTRING(p.code FROM 9)
        WHEN p.code LIKE 'dashboards.%' THEN SUBSTRING(p.code FROM 12)
        WHEN p.code IN ('APPROVE_PURCHASE','DELETE_PO','role-manage','permission-manage','permission-scope-manage','system-configure') THEN p.code
        WHEN UPPER(p.code) LIKE '%USERS' OR p.code LIKE 'user-%' THEN 'USER_MANAGEMENT'
        WHEN p.code LIKE 'access-%' THEN 'ACCESS'
        WHEN p.code LIKE 'audit-%' THEN 'AUDIT'
        WHEN UPPER(p.code) LIKE '%PURCHASE' THEN 'PURCHASE'
        WHEN UPPER(p.code) LIKE '%WAREHOUSE' THEN 'WAREHOUSE'
        WHEN p.code LIKE 'REPORT_%' THEN 'REPORT'
        WHEN p.code LIKE 'company-%' THEN 'COMPANY'
        WHEN p.code LIKE 'scope.%' THEN 'SCOPE'
        ELSE UPPER(COALESCE(p.module_name, 'GENERIC'))
    END AS permission_key,
    CASE
        WHEN p.code LIKE 'reports.%' OR p.code LIKE 'dashboards.%' THEN 'ALLOW'
        WHEN p.code IN ('role-manage','permission-manage','permission-scope-manage','system-configure') THEN 'ALLOW'
        WHEN UPPER(p.code) LIKE '%MANAGE%'
          OR UPPER(p.code) LIKE '%WRITE%'
          OR UPPER(p.code) LIKE '%CREATE%'
          OR UPPER(p.code) LIKE '%DELETE%'
          OR UPPER(p.code) LIKE '%UPDATE%'
          OR UPPER(p.code) LIKE '%APPROVE%'
          OR UPPER(p.code) LIKE '%EXPORT%'
          OR UPPER(p.code) LIKE '%IMPORT%'
          OR UPPER(p.code) LIKE '%ADMIN%'
          OR UPPER(p.code) LIKE '%CONFIGURE%'
        THEN 'MANAGE'
        ELSE 'VIEW'
    END AS grant_type
FROM permissions p
CROSS JOIN roles r
WHERE r.name = 'ADMIN'
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp
      WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

-- Bump authz_sync_version so any running permission-service cache (and frontend
-- /authz/version polling) picks up the new grants on first poll. UPSERT guards
-- against silent no-op if V6 seed row is ever missing on a fresh schema.
INSERT INTO authz_sync_version (id, version, updated_at)
VALUES (1, 1, NOW())
ON CONFLICT (id) DO UPDATE
SET version = authz_sync_version.version + 1,
    updated_at = NOW();

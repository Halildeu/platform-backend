-- V17 — Role-level permission model marker (Plan C closure)
--
-- Codex 019dd818 iter-16 (Plan C):
-- iter-15 retrospective review identified two residual closure gaps in the
-- 3-layer fix (read/write/boot) that ship-closed iter-14:
--
-- 1. Empty granule role boot bug: PermissionDataInitializer.usesGranuleModel()
--    inspects role.rolePermissions row presence. If a user clears all
--    permissions via PUT /granules (body permissions: []), the role ends up
--    with zero rows. On next boot, usesGranuleModel returns false, the
--    legacy seed flow re-creates DEFAULT_ROLE_PERMISSIONS FK rows, and the
--    same "saved empty, reopened with content" symptom returns.
--
-- 2. Legacy write endpoints still active on granule roles: PUT
--    /v1/roles/{id}/permissions and PATCH /v1/roles/{id}/permissions/bulk
--    continue to write FK rows via AccessRoleService.updateRolePermissions
--    / applyLevelForModule. If invoked against a granule-managed role they
--    silently re-create mixed FK + granule state.
--
-- Plan C — role-level mode marker (this migration + companion code change):
--
--   * Add roles.permission_model NOT NULL DEFAULT 'LEGACY'.
--   * Backfill 'GRANULE' for any role that currently carries at least one
--     granule-shape row (permission_id NULL + permission_type/key/grant set).
--     The 6 FK-only roles (PURCHASE_MANAGER, FINANCE_VIEWER,
--     PERMISSION_MANAGE, USER_MANAGER, VARIANT_SCOPE_CANARY,
--     FULL_ACCESS_EXTRA on the live test cluster as of 2026-04-29) stay on
--     LEGACY and continue to flow through PermissionDataInitializer.
--   * Companion application code (PR #31):
--       a) AccessControllerV1.updateRoleGranules sets
--          role.permissionModel = GRANULE on every call, including the empty
--          replace path.
--       b) PermissionDataInitializer skips seed when
--          role.permissionModel == GRANULE, independently of current row
--          presence.
--       c) AccessControllerV1.bulkPermissions and updateRolePermissions
--          return 409 Conflict for granule-managed roles.
--
-- The marker converts the implicit row-shape signal (used by iter-14) into
-- an explicit role-level invariant the persistence layer can enforce.
-- Combined with the iter-13 aggregate-native replace (Plan B) and the
-- iter-12 read-path canonicalize (Plan A+), this closes the cycle: every
-- entry point — read, write (canonical + legacy), boot — is now mode-aware.

ALTER TABLE roles
    ADD COLUMN permission_model VARCHAR(20) NOT NULL DEFAULT 'LEGACY';

-- Backfill existing granule-managed roles based on row evidence so the
-- 11 roles identified by iter-14 (ROLE_MANAGE, ADMIN, WAREHOUSE_OPERATOR,
-- FINANCE_MANAGER, SYSTEM_CONFIGURE, AUDIT_READ, USER_VIEWER,
-- REPORT_VIEWER, USER_MANAGE, REPORT_MANAGER + ADMIN dup) keep their
-- runtime classification across V17.
UPDATE roles r
SET permission_model = 'GRANULE'
WHERE EXISTS (
    SELECT 1
    FROM role_permissions rp
    WHERE rp.role_id = r.id
      AND rp.permission_id IS NULL
      AND rp.permission_type IS NOT NULL
      AND rp.permission_key IS NOT NULL
      AND rp.grant_type IS NOT NULL
);

-- Lock the value space; future migrations can extend the enum but invalid
-- values fail fast instead of silently corrupting the marker.
ALTER TABLE roles
    ADD CONSTRAINT ck_roles_permission_model
    CHECK (permission_model IN ('LEGACY', 'GRANULE'));

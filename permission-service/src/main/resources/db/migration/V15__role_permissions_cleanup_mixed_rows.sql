-- V15 — Cleanup mixed FK + granule rows in role_permissions
--
-- Codex 019dd818 iter-13 (Plan B) data healing:
-- updateRoleGranules controller previously combined JPQL bulk DELETE with
-- managed-entity cascade save. Bulk DELETE bypassed the persistence context;
-- subsequent `roleRepository.save(role)` cascade-persisted the stale Role
-- collection state, resurrecting the deleted FK-bearing rows. Live DB
-- dry-run (2026-04-29 11:23) found 8 roles with 39 legacy FK rows mixed
-- alongside the new granule rows:
--
--   role_id | name               | legacy_rows
--   --------+--------------------+------------
--      1   | ROLE_MANAGE        |  1
--      2   | ADMIN              | 29
--      3   | WAREHOUSE_OPERATOR |  2
--      5   | SYSTEM_CONFIGURE   |  1
--      6   | AUDIT_READ         |  1
--      7   | USER_VIEWER        |  1
--      8   | REPORT_VIEWER      |  1
--     13   | REPORT_MANAGER     |  3
--
-- Symptom: AccessRoleService.deriveLevel() iterates ALL rows in the byModule
-- group; legacy FK codes (e.g. user-admin) trigger 'admin contains' branch
-- → MANAGE returned, even when the user explicitly saved VIEW. Drawer
-- displays MANAGE; user perceives "kayıt yanlış".
--
-- Cleanup rule: For any role that already has at least one granule-style row
-- (permission_id NULL, full type/key/grant populated), the FK-bearing rows
-- are NOT authoritative and must be removed. Roles that still rely entirely
-- on the legacy FK model are untouched.
--
-- The runtime fix (Role.clearRolePermissions + addRolePermission, controller
-- aggregate-native replace) prevents future drift; this migration heals
-- existing data so the next role detail GET returns the correct level.

DELETE FROM role_permissions rp
WHERE rp.permission_id IS NOT NULL
  AND EXISTS (
    SELECT 1
    FROM role_permissions g
    WHERE g.role_id = rp.role_id
      AND g.permission_id IS NULL
      AND g.permission_type IS NOT NULL
      AND g.permission_key IS NOT NULL
      AND g.grant_type IS NOT NULL
  );

-- P2 follow-up (Codex iter-13): partial unique index on granule rows
-- (role_id, permission_type, permission_key) WHERE permission_id IS NULL
-- to prevent duplicate granule inserts. Defer to a separate migration after
-- live drift is fully drained — adding the constraint here would fail if
-- any duplicate granule rows still exist.

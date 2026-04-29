-- V16 — Re-run the V15 cleanup after PermissionDataInitializer drift is gone
--
-- Codex 019dd818 iter-14 (Plan A + V16) data healing:
-- V15 successfully cleaned mixed FK rows on its first startup, but
-- PermissionDataInitializer.run() — which is granule-blind in pre-iter-14
-- code — re-seeded the legacy FK rows on the same boot, recreating the
-- mixed state V15 had just removed. Live diagnostic post-V15 deploy:
--
--   role_id | name               | legacy_rows_resurrected
--   --------+--------------------+------------------------
--      1   | ROLE_MANAGE        |  1
--      2   | ADMIN              | 29
--      3   | WAREHOUSE_OPERATOR |  2
--      4   | FINANCE_MANAGER    |  5
--      5   | SYSTEM_CONFIGURE   |  1
--      6   | AUDIT_READ         |  1
--      7   | USER_VIEWER        |  1
--      8   | REPORT_VIEWER      |  1
--      9   | USER_MANAGE        |  3
--     13   | REPORT_MANAGER     |  3
--   total: 47 mixed FK rows
--
-- This migration is paired with the iter-14 PermissionDataInitializer fix:
-- the initializer now skips legacy FK seeding when a role already has at
-- least one granule row (`permission_id IS NULL` + full type/key/grant).
-- That fix prevents future drift; this migration heals the data one more
-- time so the next role detail GET returns the correct level.
--
-- Why a separate migration instead of re-running V15: Flyway records V15
-- as already applied (success=t); it will not re-execute on subsequent
-- pod startups. V16 is a fresh checksum so it runs once and converges
-- the table to the canonical granule-only state for granule-mode roles.
--
-- Cleanup rule (identical to V15): for any role that already has at least
-- one granule-style row, the FK-bearing rows are NOT authoritative and
-- must be removed. Roles still on the legacy FK-only model are untouched.

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

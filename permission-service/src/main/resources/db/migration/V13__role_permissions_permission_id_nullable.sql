-- STORY-0318 / OI-03: role_permissions.permission_id nullable for granule-only rows.
-- V2 created the column as NOT NULL REFERENCES permissions(id). V5 added the
-- granule columns (permission_type / permission_key / grant_type) as NOT NULL
-- but left permission_id's stale NOT NULL in place.
--
-- AccessControllerV1.updateRoleGranules (STORY-0318) inserts rows with
-- permission_id = NULL; the RolePermission JPA mapping already treats the
-- column as nullable; PermissionService.syncTuplesToOpenFga / hasPermission
-- already null-guard granule-only rows (CNS-20260416-001 B3 fix). The DB
-- constraint was the only remaining enforcement gap.
--
-- Canary evidence (2026-04-18): granule POSTs 500 with
--   "null value in column \"permission_id\" of relation \"role_permissions\" violates not-null constraint"
-- for all 5 canary roles; 2/4 user-role assignments cascade-fail on audit
-- snapshotRole NPE once granule rows exist without permission_id.
--
-- Safety: dropping NOT NULL is backward-compat. Legacy rows (ADMIN V12 grants)
-- keep their non-null permission_id and existing uk_role_permissions_role_permission
-- unique index continues to enforce (role_id, permission_id) for them.
--
-- Granule-row uniqueness: add a partial unique index so
-- (role_id, permission_type, permission_key) stays unique among NULL
-- permission_id rows (Postgres default NULLS DISTINCT otherwise lets duplicates
-- through on the legacy index).

ALTER TABLE role_permissions
    ALTER COLUMN permission_id DROP NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_role_permissions_role_granule
    ON role_permissions(role_id, permission_type, permission_key)
    WHERE permission_id IS NULL;

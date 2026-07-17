-- V21 — board #2593: a role that GRANTS endpoint-admin read access, so the permission
-- can be delegated through the product (POST /api/v1/roles/{id}/members) instead of only
-- through the deploy-time bootstrap-email list.
--
-- Why this exists. endpoint-admin's device grid is guarded by
--   @RequireModule(module = "endpoint-admin", relation = "can_view")
-- and the FGA tuple that satisfies it (user:<id>#can_view@module:endpoint-admin) was, until
-- now, written ONLY by the bootstrap runner at pod start. No role in role_permissions carried
-- an endpoint-admin granule, so adding a user to any existing role (ADMIN included) never
-- opened endpoint-admin — measured live on testai 2026-07-17: grant API returned 200 but the
-- target stayed 403. That made "who can see the fleet" a deploy-time list, not something an
-- admin can delegate. This role closes that gap: PermissionService.assignRole runs the
-- GAIN-reconcile (#1275), which turns this MODULE granule into the can_view tuple immediately.
--
-- Deliberately does NOT assign the role to anyone. Seeding the capability is a schema change;
-- handing it to a person is an authorization decision that stays with an operator (or the
-- bootstrap list for the first admin). MANAGER (uninstall/approve) is a separate slice.
--
-- Idempotent: safe to re-run, and a no-op if the role already exists.

INSERT INTO roles (name, description, created_at, updated_at, permission_model)
SELECT 'ENDPOINT_ADMIN_VIEWER',
       'Grants read-only access to endpoint-admin viewer APIs and UI (board #2593) — the device '
       || 'inventory and the other can_view surfaces (audit, command history, compliance). Does '
       || 'NOT grant management actions. Assign a user to this role to let them view the fleet '
       || 'without adding them to the bootstrap-admin list.',
       now(), now(), 'GRANULE'
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE name = 'ENDPOINT_ADMIN_VIEWER');

-- Convergent idempotency, not just exact-state idempotency: if a role with this name already
-- exists from an earlier hand-seed but is marked LEGACY, the granule below would be resolved
-- through the legacy path and never reach OpenFGA. Force the marker so a re-run converges the
-- role to the state this migration intends, whatever it found.
UPDATE roles SET permission_model = 'GRANULE', updated_at = now()
WHERE name = 'ENDPOINT_ADMIN_VIEWER' AND permission_model IS DISTINCT FROM 'GRANULE';

-- The granule. permission_key MUST be the exact FGA object id 'endpoint-admin' (kebab-case,
-- matching EndpointAdminAuthz.MODULE): TupleSyncService writes the key verbatim as the object
-- id, so 'ENDPOINT_ADMIN' would produce module:ENDPOINT_ADMIN and never satisfy the guard.
-- grant_type 'VIEW' is the canonical MODULE read verb (maps to can_view).
-- Converge any pre-existing endpoint-admin granule on this role to VIEW first, so a re-run that
-- finds a different grant_type (e.g. a hand-seeded MANAGE) does not leave a second, conflicting
-- row — the effective-grant resolver would then widen the role beyond read-only.
UPDATE role_permissions rp
SET grant_type = 'VIEW'
FROM roles r
WHERE rp.role_id = r.id
  AND r.name = 'ENDPOINT_ADMIN_VIEWER'
  AND rp.permission_type = 'MODULE'
  AND rp.permission_key = 'endpoint-admin'
  AND rp.grant_type IS DISTINCT FROM 'VIEW';

INSERT INTO role_permissions (role_id, permission_type, permission_key, grant_type)
SELECT r.id, 'MODULE', 'endpoint-admin', 'VIEW'
FROM roles r
WHERE r.name = 'ENDPOINT_ADMIN_VIEWER'
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp
      WHERE rp.role_id = r.id
        AND rp.permission_type = 'MODULE'
        AND rp.permission_key = 'endpoint-admin'
  );

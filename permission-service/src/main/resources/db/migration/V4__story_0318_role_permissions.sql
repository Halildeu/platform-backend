-- STORY-0318: Zanzibar role permissions — permission_type + permission_key + grant_type
-- Enables: role as permission package (module + action + report + page + field), DENY support

-- 1. Add new columns (nullable first for migration)
ALTER TABLE role_permissions
  ADD COLUMN IF NOT EXISTS permission_type VARCHAR(20),
  ADD COLUMN IF NOT EXISTS permission_key  VARCHAR(100),
  ADD COLUMN IF NOT EXISTS grant_type      VARCHAR(10);

-- 2. Backfill existing rows from permissions table
UPDATE role_permissions rp
SET permission_type = 'MODULE',
    permission_key  = COALESCE(p.module_name, 'UNKNOWN'),
    grant_type      = CASE
      WHEN p.code ILIKE '%MANAGE%' THEN 'MANAGE'
      WHEN p.code ILIKE '%WRITE%'  THEN 'MANAGE'
      WHEN p.code ILIKE '%CREATE%' THEN 'MANAGE'
      WHEN p.code ILIKE '%DELETE%' THEN 'MANAGE'
      WHEN p.code ILIKE '%UPDATE%' THEN 'MANAGE'
      ELSE 'VIEW'
    END
FROM permissions p
WHERE p.id = rp.permission_id
  AND rp.permission_type IS NULL;

-- 3. Handle orphan rows (no matching permission)
UPDATE role_permissions
SET permission_type = 'MODULE',
    permission_key  = 'UNKNOWN',
    grant_type      = 'VIEW'
WHERE permission_type IS NULL;

-- 4. Remove EDIT — convert to MANAGE
UPDATE role_permissions SET grant_type = 'MANAGE' WHERE grant_type = 'EDIT';

-- 5. Set NOT NULL constraints
ALTER TABLE role_permissions
  ALTER COLUMN permission_type SET NOT NULL,
  ALTER COLUMN permission_key SET NOT NULL,
  ALTER COLUMN grant_type SET NOT NULL;

-- 6. Composite index for efficient lookup
CREATE INDEX IF NOT EXISTS idx_role_perm_type_key
  ON role_permissions(role_id, permission_type, permission_key);

-- 7. Add branch_id to user_role_assignments for scope support
ALTER TABLE user_role_assignments
  ADD COLUMN IF NOT EXISTS branch_id BIGINT;

-- ============================================================
-- Row-Level Security (RLS) Policies
-- Defense-in-depth: even if Hibernate @Filter is bypassed
-- (native query, entityManager.find), DB enforces scope.
--
-- How it works:
-- 1. Application sets session variable per-request:
--    SET LOCAL app.scope.company_ids = '1,5,10';
-- 2. RLS policy checks company_id against this variable
-- 3. If variable is not set (dev mode), all rows visible
-- ============================================================

-- =====================
-- user_service.users
-- =====================
ALTER TABLE user_service.users ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_service.users FORCE ROW LEVEL SECURITY;

CREATE POLICY company_scope_users ON user_service.users
  FOR ALL
  USING (
    -- NULL company_id = global record, always visible
    company_id IS NULL
    -- If session var not set (dev/test mode), show all
    OR current_setting('app.scope.company_ids', true) IS NULL
    OR current_setting('app.scope.company_ids', true) = ''
    -- SuperAdmin bypass
    OR current_setting('app.scope.bypass_rls', true) = 'true'
    -- Normal check: company_id must be in allowed list
    OR company_id = ANY(
      string_to_array(current_setting('app.scope.company_ids', true), ',')::bigint[]
    )
  );

-- =====================
-- permission_service.user_role_assignments
-- =====================
ALTER TABLE permission_service.user_role_assignments ENABLE ROW LEVEL SECURITY;
ALTER TABLE permission_service.user_role_assignments FORCE ROW LEVEL SECURITY;

CREATE POLICY company_scope_assignments ON permission_service.user_role_assignments
  FOR ALL
  USING (
    company_id IS NULL
    OR current_setting('app.scope.company_ids', true) IS NULL
    OR current_setting('app.scope.company_ids', true) = ''
    OR current_setting('app.scope.bypass_rls', true) = 'true'
    OR company_id = ANY(
      string_to_array(current_setting('app.scope.company_ids', true), ',')::bigint[]
    )
  );

-- ============================================================
-- IMPORTANT NOTES:
-- 1. Table OWNER bypasses RLS. Services should connect as
--    a non-owner role for RLS to take effect.
--    For dev/local mode, we use FORCE ROW LEVEL SECURITY
--    which applies even to table owner.
--
-- 2. SET LOCAL is transaction-scoped (cleared on COMMIT).
--    HikariCP returns connections to pool after COMMIT,
--    so stale settings don't leak between requests.
--
-- 3. Dev mode: if app.scope.company_ids is never SET,
--    current_setting(..., true) returns NULL → all rows visible.
--
-- 4. To test RLS manually:
--    SET LOCAL app.scope.company_ids = '1';
--    SELECT * FROM user_service.users;  -- only company 1
--    SET LOCAL app.scope.company_ids = '1,5';
--    SELECT * FROM user_service.users;  -- companies 1 and 5
-- ============================================================

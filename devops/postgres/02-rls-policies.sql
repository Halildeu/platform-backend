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
--
-- IDEMPOTENT + FRESH-BOOT SAFE (2026-04-14):
-- This script runs from /docker-entrypoint-initdb.d/ on fresh Postgres
-- volumes, BEFORE any application starts and BEFORE Flyway migrations
-- create the target tables. Without existence guards, `ALTER TABLE`
-- fails with "relation does not exist" and postgres exits (code 3),
-- cascading to every backend service that depends on postgres health.
--
-- Each RLS block is therefore wrapped in DO $$ / IF EXISTS so fresh
-- boot skips silently and the same script becomes a no-op on subsequent
-- runs (policy already present). Once Flyway has created the tables on
-- first app startup, run this file manually via psql to apply RLS, or
-- rely on it on the next cold boot (volume preserved).
-- ============================================================

-- =====================
-- user_service.users
-- =====================
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.tables
    WHERE table_schema = 'user_service' AND table_name = 'users'
  ) THEN
    ALTER TABLE user_service.users ENABLE ROW LEVEL SECURITY;
    ALTER TABLE user_service.users FORCE ROW LEVEL SECURITY;

    IF NOT EXISTS (
      SELECT 1 FROM pg_policies
      WHERE schemaname = 'user_service'
        AND tablename = 'users'
        AND policyname = 'company_scope_users'
    ) THEN
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
      RAISE NOTICE '[rls] user_service.users: policy created';
    ELSE
      RAISE NOTICE '[rls] user_service.users: policy already exists';
    END IF;
  ELSE
    RAISE NOTICE '[rls] user_service.users: table not found, skipping (Flyway not yet run on fresh boot)';
  END IF;
END $$;

-- =====================
-- permission_service.user_role_assignments
-- =====================
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.tables
    WHERE table_schema = 'permission_service' AND table_name = 'user_role_assignments'
  ) THEN
    ALTER TABLE permission_service.user_role_assignments ENABLE ROW LEVEL SECURITY;
    ALTER TABLE permission_service.user_role_assignments FORCE ROW LEVEL SECURITY;

    IF NOT EXISTS (
      SELECT 1 FROM pg_policies
      WHERE schemaname = 'permission_service'
        AND tablename = 'user_role_assignments'
        AND policyname = 'company_scope_assignments'
    ) THEN
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
      RAISE NOTICE '[rls] permission_service.user_role_assignments: policy created';
    ELSE
      RAISE NOTICE '[rls] permission_service.user_role_assignments: policy already exists';
    END IF;
  ELSE
    RAISE NOTICE '[rls] permission_service.user_role_assignments: table not found, skipping (Flyway not yet run on fresh boot)';
  END IF;
END $$;

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
--
-- 5. Fresh boot: this script skips silently (tables don't exist yet).
--    To apply RLS after Flyway has run on first startup:
--      docker exec platform-postgres-db-1 psql -U postgres -d users \
--        -f /docker-entrypoint-initdb.d/02-rls-policies.sql
--    Or restart postgres with volume preserved (already-applied policies
--    will be detected via pg_policies check and skipped).
-- ============================================================

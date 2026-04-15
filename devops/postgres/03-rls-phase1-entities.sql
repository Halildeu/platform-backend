-- V10: RLS Phase 1 — row-level security for permission_service.user_permission_scope and scopes tables
-- Requires PostgreSQL 9.5+ (RLS support)
-- App sets session variables via RlsScopeHelper before each transaction:
--   app.scope.bypass_rls = 'true'     → superAdmin bypass
--   app.scope.user_id = '<userId>'    → current user ID
--   app.scope.company_ids = '1,2,3'   → comma-separated company IDs

-- ============================================================
-- IDEMPOTENT + FRESH-BOOT SAFE (2026-04-15, PR #381 pattern extended):
-- Wrapped in DO $$ / IF EXISTS; fresh Postgres volumes skip silently
-- (tables created later by Flyway on first app startup).
-- Re-running this file is a no-op (policies checked via pg_policies).
-- 2026-04-15 incident: unwrapped ALTER TABLE caused postgres exit 3
-- on fresh volume after smoke cleanup, cascading 17 services down.
-- ============================================================

-- ============================================================
-- 1. permission_service.user_permission_scope RLS
-- ============================================================
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.tables
    WHERE table_schema = 'permission_service' AND table_name = 'user_permission_scope'
  ) THEN
    ALTER TABLE permission_service.user_permission_scope ENABLE ROW LEVEL SECURITY;
    ALTER TABLE permission_service.user_permission_scope FORCE ROW LEVEL SECURITY;

    IF NOT EXISTS (
      SELECT 1 FROM pg_policies
      WHERE schemaname = 'permission_service'
        AND tablename = 'user_permission_scope'
        AND policyname = 'user_scope_ups'
    ) THEN
      CREATE POLICY user_scope_ups ON permission_service.user_permission_scope
        FOR ALL
        USING (
          current_setting('app.scope.bypass_rls', true) = 'true'
          OR current_setting('app.scope.user_id', true) IS NULL
          OR user_id = CAST(current_setting('app.scope.user_id', true) AS BIGINT)
        );
      RAISE NOTICE '[rls] permission_service.user_permission_scope: policy created';
    ELSE
      RAISE NOTICE '[rls] permission_service.user_permission_scope: policy already exists';
    END IF;
  ELSE
    RAISE NOTICE '[rls] permission_service.user_permission_scope: table not found, skipping (Flyway not yet run on fresh boot)';
  END IF;
END $$;

-- ============================================================
-- 2. scopes RLS
-- ============================================================
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.tables
    WHERE table_schema = 'permission_service' AND table_name = 'scopes'
  ) THEN
    ALTER TABLE permission_service.scopes ENABLE ROW LEVEL SECURITY;
    ALTER TABLE permission_service.scopes FORCE ROW LEVEL SECURITY;

    IF NOT EXISTS (
      SELECT 1 FROM pg_policies
      WHERE schemaname = 'permission_service'
        AND tablename = 'scopes'
        AND policyname = 'company_scope_scopes'
    ) THEN
      CREATE POLICY company_scope_scopes ON permission_service.scopes
        FOR ALL
        USING (
          current_setting('app.scope.bypass_rls', true) = 'true'
          OR scope_type != 'COMPANY'
          OR current_setting('app.scope.company_ids', true) IS NULL
          OR current_setting('app.scope.company_ids', true) = ''
          OR ref_id = ANY(string_to_array(current_setting('app.scope.company_ids', true), ',')::bigint[])
        );
      RAISE NOTICE '[rls] permission_service.scopes: policy created';
    ELSE
      RAISE NOTICE '[rls] permission_service.scopes: policy already exists';
    END IF;
  ELSE
    RAISE NOTICE '[rls] permission_service.scopes: table not found, skipping (Flyway not yet run on fresh boot)';
  END IF;
END $$;

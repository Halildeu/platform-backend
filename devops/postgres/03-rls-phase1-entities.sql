-- V10: RLS Phase 1 — row-level security for user_permission_scope and scopes tables
-- Requires PostgreSQL 9.5+ (RLS support)
-- App sets session variables via RlsScopeHelper before each transaction:
--   app.scope.bypass_rls = 'true'     → superAdmin bypass
--   app.scope.user_id = '<userId>'    → current user ID
--   app.scope.company_ids = '1,2,3'   → comma-separated company IDs

-- ============================================================
-- 1. user_permission_scope RLS
-- ============================================================
ALTER TABLE user_permission_scope ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_permission_scope FORCE ROW LEVEL SECURITY;

CREATE POLICY user_scope_ups ON user_permission_scope FOR ALL USING (
    current_setting('app.scope.bypass_rls', true) = 'true'
    OR current_setting('app.scope.user_id', true) IS NULL
    OR user_id = CAST(current_setting('app.scope.user_id', true) AS BIGINT)
);

-- ============================================================
-- 2. scopes RLS
-- ============================================================
ALTER TABLE scopes ENABLE ROW LEVEL SECURITY;
ALTER TABLE scopes FORCE ROW LEVEL SECURITY;

CREATE POLICY company_scope_scopes ON scopes FOR ALL USING (
    current_setting('app.scope.bypass_rls', true) = 'true'
    OR scope_type != 'COMPANY'
    OR current_setting('app.scope.company_ids', true) IS NULL
    OR current_setting('app.scope.company_ids', true) = ''
    OR ref_id = ANY(string_to_array(current_setting('app.scope.company_ids', true), ',')::bigint[])
);

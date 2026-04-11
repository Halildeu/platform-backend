-- V19: RLS Phase 1 — row-level security for variant_visibility table
-- Enforces visibility rules at the database level:
--   GLOBAL: always visible
--   COMPANY: visible only if ref_id matches user's allowed company IDs
--   USER: visible only if ref_id matches current user ID
--   ROLE: always visible (role check is done at application layer)

ALTER TABLE variant_visibility ENABLE ROW LEVEL SECURITY;
ALTER TABLE variant_visibility FORCE ROW LEVEL SECURITY;

CREATE POLICY visibility_scope_vv ON variant_visibility FOR ALL USING (
    -- superAdmin bypass
    current_setting('app.scope.bypass_rls', true) = 'true'
    -- GLOBAL and ROLE are always visible
    OR visibility_type = 'GLOBAL'
    OR visibility_type = 'ROLE'
    -- COMPANY: check against allowed company IDs
    OR (
        visibility_type = 'COMPANY'
        AND (
            current_setting('app.scope.company_ids', true) IS NULL
            OR current_setting('app.scope.company_ids', true) = ''
            OR CAST(ref_id AS BIGINT) = ANY(string_to_array(current_setting('app.scope.company_ids', true), ',')::bigint[])
        )
    )
    -- USER: check against current user ID
    OR (
        visibility_type = 'USER'
        AND (
            current_setting('app.scope.user_id', true) IS NULL
            OR ref_id = current_setting('app.scope.user_id', true)
        )
    )
);

-- V2: RLS Phase 1 — row-level security for companies table
-- Users can only see companies they are authorized to access.
-- SuperAdmin bypasses via app.scope.bypass_rls session variable.

ALTER TABLE companies ENABLE ROW LEVEL SECURITY;
ALTER TABLE companies FORCE ROW LEVEL SECURITY;

CREATE POLICY company_scope_companies ON companies FOR ALL USING (
    current_setting('app.scope.bypass_rls', true) = 'true'
    OR current_setting('app.scope.company_ids', true) IS NULL
    OR current_setting('app.scope.company_ids', true) = ''
    OR id = ANY(string_to_array(current_setting('app.scope.company_ids', true), ',')::bigint[])
);

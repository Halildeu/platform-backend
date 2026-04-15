-- V2: RLS Phase 1 — row-level security for companies table
-- Users can only see companies they are authorized to access.
-- SuperAdmin bypasses via app.scope.bypass_rls session variable.

-- IDEMPOTENT + FRESH-BOOT SAFE (2026-04-15, PR #381 pattern extended).
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.tables
    WHERE table_schema = 'core_data_service' AND table_name = 'companies'
  ) THEN
    ALTER TABLE core_data_service.companies ENABLE ROW LEVEL SECURITY;
    ALTER TABLE core_data_service.companies FORCE ROW LEVEL SECURITY;

    IF NOT EXISTS (
      SELECT 1 FROM pg_policies
      WHERE schemaname = 'core_data_service'
        AND tablename = 'companies'
        AND policyname = 'company_scope_companies'
    ) THEN
      CREATE POLICY company_scope_companies ON core_data_service.companies
        FOR ALL
        USING (
          current_setting('app.scope.bypass_rls', true) = 'true'
          OR current_setting('app.scope.company_ids', true) IS NULL
          OR current_setting('app.scope.company_ids', true) = ''
          OR id = ANY(string_to_array(current_setting('app.scope.company_ids', true), ',')::bigint[])
        );
      RAISE NOTICE '[rls] core_data_service.companies: policy created';
    ELSE
      RAISE NOTICE '[rls] core_data_service.companies: policy already exists';
    END IF;
  ELSE
    RAISE NOTICE '[rls] core_data_service.companies: table not found, skipping (Flyway not yet run on fresh boot)';
  END IF;
END $$;

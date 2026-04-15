-- V19: RLS Phase 1 — row-level security for variant_service.variant_visibility table
-- Enforces visibility rules at the database level:
--   GLOBAL: always visible
--   COMPANY: visible only if ref_id matches user's allowed company IDs
--   USER: visible only if ref_id matches current user ID
--   ROLE: always visible (role check is done at application layer)

-- IDEMPOTENT + FRESH-BOOT SAFE (2026-04-15, PR #381 pattern extended).
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.tables
    WHERE table_schema = 'variant_service' AND table_name = 'variant_visibility'
  ) THEN
    ALTER TABLE variant_service.variant_visibility ENABLE ROW LEVEL SECURITY;
    ALTER TABLE variant_service.variant_visibility FORCE ROW LEVEL SECURITY;

    IF NOT EXISTS (
      SELECT 1 FROM pg_policies
      WHERE schemaname = 'variant_service'
        AND tablename = 'variant_visibility'
        AND policyname = 'visibility_scope_vv'
    ) THEN
      CREATE POLICY visibility_scope_vv ON variant_service.variant_visibility
        FOR ALL
        USING (
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
      RAISE NOTICE '[rls] variant_service.variant_visibility: policy created';
    ELSE
      RAISE NOTICE '[rls] variant_service.variant_visibility: policy already exists';
    END IF;
  ELSE
    RAISE NOTICE '[rls] variant_service.variant_visibility: table not found, skipping (Flyway not yet run on fresh boot)';
  END IF;
END $$;

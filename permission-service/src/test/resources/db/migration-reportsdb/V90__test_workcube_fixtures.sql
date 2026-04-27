-- Faz 21.3 PR-F (C3c): test-only fixtures for the workcube_mikrolink.* canonical
-- rows that the V19 trigger validate_scope_ref() looks up.
--
-- This migration lives ONLY in permission-service's test classpath
-- (src/test/resources/db/migration-reportsdb/) — it is never applied to a
-- production reports_db cluster. The version number sits past V20 so the
-- gitops-managed migration set (V16/V17/V19/V20) runs first and creates the
-- target tables before this fixture inserts rows.
--
-- The four PKs match the constants used by DataAccessIntegrationTest:
--   * company        '1001'  (V19 trigger's COMPANY branch)
--   * pro_projects   '1204'  (PRO_PROJECTS branch)
--   * branch         '7'     (BRANCH branch)
--   * department     '3792'  (V20 widening: depot → DEPARTMENT)
--
-- All inserts are ON CONFLICT DO NOTHING against the V17 lineage UNIQUE INDEX
-- (source_schema, source_table, source_pk), so re-running the migration on a
-- container that already has these rows is a no-op.

INSERT INTO workcube_mikrolink.company (
    source_schema, source_table, source_pk, content_hash,
    company_status, companycat_id, fullname
) VALUES (
    'workcube_mikrolink', 'COMPANY', '1001',
    'test-hash-company-1001',
    true, 1, 'Test Company 1001'
) ON CONFLICT (source_schema, source_table, source_pk) DO NOTHING;

INSERT INTO workcube_mikrolink.pro_projects (
    source_schema, source_table, source_pk, content_hash,
    project_head
) VALUES (
    'workcube_mikrolink', 'PRO_PROJECTS', '1204',
    'test-hash-project-1204',
    'Test Project 1204'
) ON CONFLICT (source_schema, source_table, source_pk) DO NOTHING;

INSERT INTO workcube_mikrolink.branch (
    source_schema, source_table, source_pk, content_hash,
    branch_status, branch_name
) VALUES (
    'workcube_mikrolink', 'BRANCH', '7',
    'test-hash-branch-7',
    true, 'Test Branch 7'
) ON CONFLICT (source_schema, source_table, source_pk) DO NOTHING;

INSERT INTO workcube_mikrolink.department (
    source_schema, source_table, source_pk, content_hash,
    department_head
) VALUES (
    'workcube_mikrolink', 'DEPARTMENT', '3792',
    'test-hash-department-3792',
    'Test Department 3792'
) ON CONFLICT (source_schema, source_table, source_pk) DO NOTHING;

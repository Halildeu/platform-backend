-- Faz 21.3 PR-F (C3c) + V25/V26 update: test-only fixtures for the
-- workcube_mikrolink.* canonical rows that data_access.validate_scope_ref()
-- looks up.
--
-- Lives ONLY in permission-service test classpath; never applied to a
-- production cluster. Version sits past V26 so the V16/V17/V19/V20/V21/
-- V22/V23/V25/V26 chain runs first, then this fixture seeds rows.
--
-- V25 anchor change (Codex 019dd34e hybrid contract):
--   * COMPANY scope now anchors workcube_mikrolink.OUR_COMPANY (tenant table,
--     COMP_ID PK), NOT the workcube_mikrolink.COMPANY directory (80,246-row).
--   * project/branch reach OUR_COMPANY via 2-hop COMPANY.OUR_COMPANY_ID FK.
--   * depot reaches OUR_COMPANY via 1-hop DEPARTMENT.OUR_COMPANY_ID FK.
--   * Tenant predicate uses data_access.organization_company as truth.
--
-- V25 also TRUNCATEs data_access.organization_company; this fixture re-seeds
-- it with the OUR_COMPANY mapping AÇIK org owns. V26 adds dual-format
-- (raw '1' / canonical '["1"]') tolerance — the OUR_COMPANY row below uses
-- the canonical JSON form to mirror live ETL contract
-- (scripts/migration/etl_worker/etl_worker/transform.py:make_source_pk).
--
-- The five PKs match the constants used by DataAccessIntegrationTest:
--   * our_company    comp_id=1, source_pk='["1"]' (V25 anchor for COMPANY)
--   * company        company_id=1, our_company_id=1 (parent for BRANCH/PRO_PROJECTS)
--   * pro_projects   '1204' company_id=1
--   * branch         '7'    company_id=1
--   * department     '3792' our_company_id=1 (V25 1-hop)
--
-- All inserts use ON CONFLICT DO NOTHING against the V17 lineage UNIQUE INDEX
-- (source_schema, source_table, source_pk). organization_company uses its
-- composite PK (org_id, workcube_company_source_pk).

-- ============================================================================
-- 1. OUR_COMPANY tenant anchor (V25)
-- ============================================================================

INSERT INTO workcube_mikrolink.our_company (
    source_schema, source_table, source_pk, content_hash,
    comp_id, company_name, nick_name
) VALUES (
    'workcube_mikrolink', 'OUR_COMPANY', '["1"]',
    'test-hash-our-company-1',
    1, 'Test Tenant Co', 'TestCo'
) ON CONFLICT (source_schema, source_table, source_pk) DO NOTHING;

-- ============================================================================
-- 2. COMPANY directory row — needed as parent for BRANCH/PRO_PROJECTS joins
--    (V25 BRANCH/PROJECT path: branch.company_id → company.company_id →
--     company.our_company_id → our_company.comp_id).
--    company_id pinned to 1 so child rows can FK to a known value.
-- ============================================================================

INSERT INTO workcube_mikrolink.company (
    source_schema, source_table, source_pk, content_hash,
    company_id, our_company_id,
    company_status, companycat_id, fullname
) VALUES (
    'workcube_mikrolink', 'COMPANY', '1001',
    'test-hash-company-1001',
    1, 1,
    true, 1, 'Test Company 1001'
) ON CONFLICT (source_schema, source_table, source_pk) DO NOTHING;

-- ============================================================================
-- 3. PRO_PROJECTS — V25 PROJECT path requires company_id FK to a tenant company
-- ============================================================================

INSERT INTO workcube_mikrolink.pro_projects (
    source_schema, source_table, source_pk, content_hash,
    company_id, project_head
) VALUES (
    'workcube_mikrolink', 'PRO_PROJECTS', '1204',
    'test-hash-project-1204',
    1, 'Test Project 1204'
) ON CONFLICT (source_schema, source_table, source_pk) DO NOTHING;

-- ============================================================================
-- 4. BRANCH — V25 BRANCH path requires company_id FK to a tenant company
-- ============================================================================

INSERT INTO workcube_mikrolink.branch (
    source_schema, source_table, source_pk, content_hash,
    company_id, branch_status, branch_name
) VALUES (
    'workcube_mikrolink', 'BRANCH', '7',
    'test-hash-branch-7',
    1, true, 'Test Branch 7'
) ON CONFLICT (source_schema, source_table, source_pk) DO NOTHING;

-- ============================================================================
-- 5. DEPARTMENT — V25 DEPOT path uses 1-hop our_company_id FK
-- ============================================================================

INSERT INTO workcube_mikrolink.department (
    source_schema, source_table, source_pk, content_hash,
    our_company_id, department_head
) VALUES (
    'workcube_mikrolink', 'DEPARTMENT', '3792',
    'test-hash-department-3792',
    1, 'Test Department 3792'
) ON CONFLICT (source_schema, source_table, source_pk) DO NOTHING;

-- ============================================================================
-- 6. organization_company re-seed (V25 truncated this; V19 CROSS JOIN seed
--    captured 0 rows because workcube_mikrolink.company was empty at V19
--    migration time). source_table now 'OUR_COMPANY' per V25 CHECK; the
--    canonical JSON form '["1"]' mirrors live ETL — V26 dual-format
--    tolerance covers raw form too.
-- ============================================================================

INSERT INTO data_access.organization_company (
    org_id, workcube_company_source_pk, source_schema, source_table
)
SELECT o.id, '["1"]', 'workcube_mikrolink', 'OUR_COMPANY'
  FROM data_access.organization o
 WHERE o.name = 'AÇIK'
ON CONFLICT (org_id, workcube_company_source_pk) DO NOTHING;

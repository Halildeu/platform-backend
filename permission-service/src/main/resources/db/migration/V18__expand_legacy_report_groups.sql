-- Codex 019dda1c iter-27: expand legacy report group keys into per-dashboard
-- granule rows so existing role assignments line up with the iter-26 catalog
-- (12 fine-grained dashboards under İnsan Kaynakları / Finans).
--
-- Source rows (granule-shape, permission_id IS NULL) for these keys exist
-- because earlier seeds (V12 admin role, V8 permission group matrix) granted
-- coarse "HR_REPORTS / FINANCE_REPORTS / ANALYTICS_REPORTS" entries that the
-- iter-26 catalog no longer exposes. The drawer save flow's stale-key filter
-- (mfe-access iter-26) drops them at write time, but the legacy rows remain
-- and the affected roles miss the per-dashboard permissions the new UI
-- expects users to grant.
--
-- IMPORTANT: HR_REPORTS and FINANCE_REPORTS group keys MUST NOT be deleted
-- here because the legacy report-service static reports
-- (report-service/src/main/resources/reports/*.json) still reference them in
-- their "reportGroup" field. Purging would break access to those reports.
-- Only ANALYTICS_REPORTS is fully retired in this migration; the other two
-- group keys are preserved for backward compatibility with static reports.

-- Expansion rules (Codex iter-27 review):
--   HR_REPORTS        -> 9 HR dashboards (every hr-*.json key)
--   FINANCE_REPORTS   -> 3 Finans dashboards (every fin-*.json key)
--   ANALYTICS_REPORTS -> HR_ANALYTICS + HR_FINANSAL + FIN_ANALYTICS (V8 history)
--
-- grant_type normalization: ALLOW -> VIEW (drawer UI contract). MANAGE / DENY
-- preserved as-is.

-- Step 1. Insert per-dashboard granule rows derived from legacy group rows.
INSERT INTO role_permissions (role_id, permission_id, permission_type, permission_key, grant_type)
SELECT
    src.role_id,
    NULL,
    'REPORT',
    new_key,
    src.grant_type
FROM (
    SELECT
        rp.role_id,
        rp.permission_key AS legacy_key,
        CASE rp.grant_type
            WHEN 'MANAGE' THEN 'MANAGE'
            WHEN 'DENY'   THEN 'DENY'
            ELSE 'VIEW'
        END AS grant_type
    FROM role_permissions rp
    WHERE rp.permission_id IS NULL
      AND rp.permission_type = 'REPORT'
      AND rp.permission_key IN ('HR_REPORTS', 'FINANCE_REPORTS', 'ANALYTICS_REPORTS')
) src
CROSS JOIN LATERAL (
    SELECT unnest(
        CASE src.legacy_key
            WHEN 'HR_REPORTS' THEN ARRAY[
                'HR_ANALYTICS', 'HR_FINANSAL', 'HR_EQUITY_RISK',
                'HR_BENEFITS_LITE', 'HR_COMPENSATION', 'HR_SALARY_ANALYTICS',
                'HR_PAYROLL_TRENDS', 'HR_DEMOGRAFIK', 'HR_EXECUTIVE_SUMMARY'
            ]::text[]
            WHEN 'FINANCE_REPORTS' THEN ARRAY[
                'FIN_ANALYTICS', 'FIN_RATIOS', 'FIN_RECONCILIATION'
            ]::text[]
            WHEN 'ANALYTICS_REPORTS' THEN ARRAY[
                'HR_ANALYTICS', 'HR_FINANSAL', 'FIN_ANALYTICS'
            ]::text[]
        END
    ) AS new_key
) expansion
ON CONFLICT (role_id, permission_type, permission_key) WHERE permission_id IS NULL DO NOTHING;

-- Step 2. Mark every affected role as GRANULE so the boot-time
-- PermissionDataInitializer's row-shape predicate doesn't misclassify them
-- and re-seed FK rows over the new dashboard granules.
UPDATE roles
SET permission_model = 'GRANULE'
WHERE id IN (
    SELECT DISTINCT rp.role_id
    FROM role_permissions rp
    WHERE rp.permission_id IS NULL
      AND rp.permission_type = 'REPORT'
      AND rp.permission_key IN (
        'HR_ANALYTICS', 'HR_FINANSAL', 'HR_EQUITY_RISK',
        'HR_BENEFITS_LITE', 'HR_COMPENSATION', 'HR_SALARY_ANALYTICS',
        'HR_PAYROLL_TRENDS', 'HR_DEMOGRAFIK', 'HR_EXECUTIVE_SUMMARY',
        'FIN_ANALYTICS', 'FIN_RATIOS', 'FIN_RECONCILIATION'
      )
);

-- Step 3. Schedule OpenFGA tuple sync for affected roles. Flyway only mutates
-- the DB rows; without an outbox PENDING row, the OpenFGA tuples for the new
-- per-dashboard grants would not propagate until the next live drawer save.
INSERT INTO tuple_sync_outbox (role_id, status, created_at)
SELECT DISTINCT rp.role_id, 'PENDING', NOW()
FROM role_permissions rp
WHERE rp.permission_id IS NULL
  AND rp.permission_type = 'REPORT'
  AND rp.permission_key IN (
    'HR_ANALYTICS', 'HR_FINANSAL', 'HR_EQUITY_RISK',
    'HR_BENEFITS_LITE', 'HR_COMPENSATION', 'HR_SALARY_ANALYTICS',
    'HR_PAYROLL_TRENDS', 'HR_DEMOGRAFIK', 'HR_EXECUTIVE_SUMMARY',
    'FIN_ANALYTICS', 'FIN_RATIOS', 'FIN_RECONCILIATION'
  );

-- Step 4. Retire ANALYTICS_REPORTS — no static report references it
-- (verified in report-service/src/main/resources/reports/*.json) and the
-- iter-26 catalog dropped it. HR_REPORTS and FINANCE_REPORTS are preserved.
DELETE FROM role_permissions
WHERE permission_id IS NULL
  AND permission_type = 'REPORT'
  AND permission_key = 'ANALYTICS_REPORTS';

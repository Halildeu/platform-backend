-- V14 — Canonicalize legacy `permissions.module_name` values
--
-- Codex 019dd818 iter-11/12 (Plan A+) data normalization:
-- Live diagnostic (2026-04-29) revealed that `permissions.module_name`
-- contains mixed Turkish UI labels and legacy lowercase keys instead of
-- the canonical module key set (USER_MANAGEMENT, ACCESS, AUDIT, REPORT,
-- WAREHOUSE, PURCHASE, THEME from PermissionCatalogService.MODULES).
--
-- This drift caused AccessRoleService.toDto() to group role_permissions
-- by mangled labels ("Sistem Yönetimi", "reporting") that the frontend
-- catalog cannot match, rendering every role drawer's MODULES section
-- as "—" (NONE).
--
-- This migration normalizes the data layer; runtime forward-compat is
-- handled by service.ModuleNameCanonicalizer for any future drift
-- (test fixtures, manual SQL inserts).
--
-- Out-of-scope (P2 follow-up):
--   - "Variant" → catalog expansion decision (new canonical VARIANT)
--   - "Company" → catalog expansion decision (new canonical COMPANY)
--   - "scope" → cross-cutting bypass action; refactor to PermissionType=ACTION

UPDATE permissions
SET module_name = 'USER_MANAGEMENT'
WHERE module_name = 'Kullanıcı Yönetimi';

UPDATE permissions
SET module_name = 'ACCESS'
WHERE module_name = 'Access';

UPDATE permissions
SET module_name = 'AUDIT'
WHERE module_name = 'Audit';

UPDATE permissions
SET module_name = 'REPORT'
WHERE module_name IN ('reporting', 'Raporlama');

UPDATE permissions
SET module_name = 'WAREHOUSE'
WHERE module_name = 'Depo';

UPDATE permissions
SET module_name = 'PURCHASE'
WHERE module_name = 'Satın Alma';

UPDATE permissions
SET module_name = 'THEME'
WHERE module_name = 'Tema Yönetimi';

-- "Sistem Yönetimi" → ACCESS only for the 4 well-known role/permission
-- management codes. Whitelist guard prevents silent drift if a future
-- permission with a different intent gets the same legacy label.
UPDATE permissions
SET module_name = 'ACCESS'
WHERE module_name = 'Sistem Yönetimi'
  AND code IN (
    'permission-manage',
    'permission-scope-manage',
    'role-manage',
    'system-configure'
  );

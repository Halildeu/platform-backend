-- P1-B: Permission grouping matrix
-- Consultation CNS-20260410-001: reports.* → 4 groups, user-* → 3 tiers
-- This migration maps granular permission_keys to canonical groups
-- Existing permissions are ARCHIVED (not deleted) for audit trail

-- 1. Archive granular HR reports → HR_REPORTS group
UPDATE role_permissions SET permission_key = 'HR_REPORTS'
WHERE permission_type = 'REPORT'
  AND permission_key IN ('hr-personel-listesi', 'hr-maas-raporu', 'hr-giris-cikis',
      'hr-puantaj', 'hr-maas-gecmisi', 'hr-egitim-katilim', 'hr-izin-raporu', 'hr-bordro-detay');

-- 2. Archive granular Finance reports → FINANCE_REPORTS group
UPDATE role_permissions SET permission_key = 'FINANCE_REPORTS'
WHERE permission_type = 'REPORT'
  AND permission_key IN ('fin-banka-hareketleri', 'fin-kasa-hareketleri', 'fin-faturalar',
      'fin-cek-senet', 'fin-cari-hareketler');

-- 3. Archive granular Sales reports → SALES_REPORTS group
UPDATE role_permissions SET permission_key = 'SALES_REPORTS'
WHERE permission_type = 'REPORT'
  AND permission_key IN ('satis-ozet', 'stok-durum');

-- 4. Archive dashboards → ANALYTICS_REPORTS group
UPDATE role_permissions SET permission_key = 'ANALYTICS_REPORTS'
WHERE permission_type = 'REPORT'
  AND permission_key IN ('fin-analytics', 'hr-analytics', 'hr-finansal',
      'fin-analytics.view', 'hr-analytics.view', 'hr-finansal.view');

-- 5. all-companies → keep as scope markers, rename for clarity
UPDATE role_permissions SET permission_key = 'SCOPE_ALL_COMPANIES_HR'
WHERE permission_type = 'REPORT' AND permission_key = 'hr.all-companies';

UPDATE role_permissions SET permission_key = 'SCOPE_ALL_COMPANIES_FIN'
WHERE permission_type = 'REPORT' AND permission_key = 'fin.all-companies';

-- 6. Deduplicate role_permissions (remove duplicate rows after grouping)
DELETE FROM role_permissions a USING role_permissions b
WHERE a.id > b.id
  AND a.role_id = b.role_id
  AND a.permission_type = b.permission_type
  AND a.permission_key = b.permission_key
  AND a.grant_type = b.grant_type;

-- 7. Archive old granular permission definitions
UPDATE permissions SET description = CONCAT('[ARCHIVED-P1B] ', description)
WHERE code LIKE 'reports.hr-%' OR code LIKE 'reports.fin-%' OR code LIKE 'reports.satis-%'
   OR code LIKE 'reports.stok-%' OR code LIKE 'reports.%.all-companies';

-- P1-A: Remove PAGE and FIELD permission types
-- Consultation CNS-20260410-001: page/field are UI concerns, not Zanzibar objects
-- ADMIN_SETTINGS/PURCHASE_SETTINGS → handled by hasModule('ACCESS'/'PURCHASE')
-- field type was never used (zero catalog entries, zero tuples)

-- Delete PAGE/FIELD role_permission rows (data preserved via audit, not moved to REPORT)
DELETE FROM role_permissions WHERE permission_type = 'PAGE';
DELETE FROM role_permissions WHERE permission_type = 'FIELD';

-- Archive dashboard permissions in description for traceability
UPDATE permissions
SET description = CONCAT('[ARCHIVED-P1] ', description)
WHERE code LIKE 'dashboards.%';

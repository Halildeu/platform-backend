-- V9: Final cleanup of PAGE/FIELD rows that survived V7 migration
-- V7 targeted role_permissions but DefaultAdminRoleAssignmentInitializer
-- may have re-inserted PAGE rows on subsequent startups.
-- This migration ensures no PAGE/FIELD data remains.

DELETE FROM permission_service.role_permissions WHERE permission_type = 'PAGE';
DELETE FROM permission_service.role_permissions WHERE permission_type = 'FIELD';

-- Also clean permissions table if any PAGE/FIELD entries exist
DELETE FROM permission_service.permissions WHERE permission_type = 'PAGE';
DELETE FROM permission_service.permissions WHERE permission_type = 'FIELD';

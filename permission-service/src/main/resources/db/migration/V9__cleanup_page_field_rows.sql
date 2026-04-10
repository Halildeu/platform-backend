-- V9: Final cleanup of PAGE/FIELD rows that survived V7 migration
-- Flyway default_schema = permission_service, so no schema prefix needed.
-- permissions table has no permission_type column — only role_permissions does.

DELETE FROM role_permissions WHERE permission_type = 'PAGE';
DELETE FROM role_permissions WHERE permission_type = 'FIELD';

BEGIN;

SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '0';

ALTER TABLE IF EXISTS auth_service.email_verification_tokens SET SCHEMA public;
ALTER TABLE IF EXISTS auth_service.password_reset_tokens SET SCHEMA public;
ALTER TABLE IF EXISTS auth_service.auth_flyway_history SET SCHEMA public;
ALTER SEQUENCE IF EXISTS auth_service.email_verification_tokens_id_seq SET SCHEMA public;
ALTER SEQUENCE IF EXISTS auth_service.password_reset_tokens_id_seq SET SCHEMA public;

ALTER TABLE IF EXISTS variant_service.user_grid_variants SET SCHEMA public;
ALTER TABLE IF EXISTS variant_service.user_grid_variant_preferences SET SCHEMA public;
ALTER TABLE IF EXISTS variant_service.variant_visibility SET SCHEMA public;
ALTER TABLE IF EXISTS variant_service.themes SET SCHEMA public;
ALTER TABLE IF EXISTS variant_service.theme_overrides SET SCHEMA public;
ALTER TABLE IF EXISTS variant_service.theme_registry SET SCHEMA public;
ALTER TABLE IF EXISTS variant_service.theme_registry_css_vars SET SCHEMA public;
ALTER TABLE IF EXISTS variant_service.user_theme_selections SET SCHEMA public;
ALTER TABLE IF EXISTS variant_service.variant_flyway_history SET SCHEMA public;
ALTER SEQUENCE IF EXISTS variant_service.variant_visibility_id_seq SET SCHEMA public;

ALTER TABLE IF EXISTS core_data_service.companies SET SCHEMA public;
ALTER TABLE IF EXISTS core_data_service.core_data_flyway_history SET SCHEMA public;
ALTER SEQUENCE IF EXISTS core_data_service.companies_id_seq SET SCHEMA public;

ALTER TABLE IF EXISTS permission_service.permissions SET SCHEMA public;
ALTER TABLE IF EXISTS permission_service.roles SET SCHEMA public;
ALTER TABLE IF EXISTS permission_service.role_permissions SET SCHEMA public;
ALTER TABLE IF EXISTS permission_service.user_role_assignments SET SCHEMA public;
ALTER TABLE IF EXISTS permission_service.permission_audit_events SET SCHEMA public;
ALTER TABLE IF EXISTS permission_service.scopes SET SCHEMA public;
ALTER TABLE IF EXISTS permission_service.user_permission_scope SET SCHEMA public;
ALTER TABLE IF EXISTS permission_service.authz_audit_log SET SCHEMA public;
ALTER TABLE IF EXISTS permission_service.permission_flyway_history SET SCHEMA public;
ALTER SEQUENCE IF EXISTS permission_service.permissions_id_seq SET SCHEMA public;
ALTER SEQUENCE IF EXISTS permission_service.roles_id_seq SET SCHEMA public;
ALTER SEQUENCE IF EXISTS permission_service.role_permissions_id_seq SET SCHEMA public;
ALTER SEQUENCE IF EXISTS permission_service.user_role_assignments_id_seq SET SCHEMA public;
ALTER SEQUENCE IF EXISTS permission_service.permission_audit_events_id_seq SET SCHEMA public;
ALTER SEQUENCE IF EXISTS permission_service.scopes_id_seq SET SCHEMA public;
ALTER SEQUENCE IF EXISTS permission_service.user_permission_scope_id_seq SET SCHEMA public;
ALTER SEQUENCE IF EXISTS permission_service.authz_audit_log_id_seq SET SCHEMA public;

ALTER TABLE IF EXISTS user_service.users SET SCHEMA public;
ALTER TABLE IF EXISTS user_service.user_audit_events SET SCHEMA public;
ALTER TABLE IF EXISTS user_service.user_notification_preferences SET SCHEMA public;
ALTER TABLE IF EXISTS user_service.user_flyway_history SET SCHEMA public;
ALTER SEQUENCE IF EXISTS user_service.users_id_seq SET SCHEMA public;
ALTER SEQUENCE IF EXISTS user_service.user_audit_events_id_seq SET SCHEMA public;
ALTER SEQUENCE IF EXISTS user_service.user_notification_preferences_id_seq SET SCHEMA public;

COMMIT;

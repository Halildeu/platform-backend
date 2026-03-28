BEGIN;

SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '0';

CREATE SCHEMA IF NOT EXISTS auth_service;
CREATE SCHEMA IF NOT EXISTS user_service;
CREATE SCHEMA IF NOT EXISTS permission_service;
CREATE SCHEMA IF NOT EXISTS variant_service;
CREATE SCHEMA IF NOT EXISTS core_data_service;

ALTER TABLE IF EXISTS public.email_verification_tokens SET SCHEMA auth_service;
ALTER TABLE IF EXISTS public.password_reset_tokens SET SCHEMA auth_service;
ALTER TABLE IF EXISTS public.auth_flyway_history SET SCHEMA auth_service;
ALTER SEQUENCE IF EXISTS public.email_verification_tokens_id_seq SET SCHEMA auth_service;
ALTER SEQUENCE IF EXISTS public.password_reset_tokens_id_seq SET SCHEMA auth_service;

ALTER TABLE IF EXISTS public.user_grid_variants SET SCHEMA variant_service;
ALTER TABLE IF EXISTS public.user_grid_variant_preferences SET SCHEMA variant_service;
ALTER TABLE IF EXISTS public.variant_visibility SET SCHEMA variant_service;
ALTER TABLE IF EXISTS public.themes SET SCHEMA variant_service;
ALTER TABLE IF EXISTS public.theme_overrides SET SCHEMA variant_service;
ALTER TABLE IF EXISTS public.theme_registry SET SCHEMA variant_service;
ALTER TABLE IF EXISTS public.theme_registry_css_vars SET SCHEMA variant_service;
ALTER TABLE IF EXISTS public.user_theme_selections SET SCHEMA variant_service;
ALTER TABLE IF EXISTS public.variant_flyway_history SET SCHEMA variant_service;
ALTER SEQUENCE IF EXISTS public.variant_visibility_id_seq SET SCHEMA variant_service;

ALTER TABLE IF EXISTS public.companies SET SCHEMA core_data_service;
ALTER TABLE IF EXISTS public.core_data_flyway_history SET SCHEMA core_data_service;
ALTER SEQUENCE IF EXISTS public.companies_id_seq SET SCHEMA core_data_service;

ALTER TABLE IF EXISTS public.permissions SET SCHEMA permission_service;
ALTER TABLE IF EXISTS public.roles SET SCHEMA permission_service;
ALTER TABLE IF EXISTS public.role_permissions SET SCHEMA permission_service;
ALTER TABLE IF EXISTS public.user_role_assignments SET SCHEMA permission_service;
ALTER TABLE IF EXISTS public.permission_audit_events SET SCHEMA permission_service;
ALTER TABLE IF EXISTS public.scopes SET SCHEMA permission_service;
ALTER TABLE IF EXISTS public.user_permission_scope SET SCHEMA permission_service;
ALTER TABLE IF EXISTS public.authz_audit_log SET SCHEMA permission_service;
ALTER TABLE IF EXISTS public.permission_flyway_history SET SCHEMA permission_service;
ALTER SEQUENCE IF EXISTS public.permissions_id_seq SET SCHEMA permission_service;
ALTER SEQUENCE IF EXISTS public.roles_id_seq SET SCHEMA permission_service;
ALTER SEQUENCE IF EXISTS public.role_permissions_id_seq SET SCHEMA permission_service;
ALTER SEQUENCE IF EXISTS public.user_role_assignments_id_seq SET SCHEMA permission_service;
ALTER SEQUENCE IF EXISTS public.permission_audit_events_id_seq SET SCHEMA permission_service;
ALTER SEQUENCE IF EXISTS public.scopes_id_seq SET SCHEMA permission_service;
ALTER SEQUENCE IF EXISTS public.user_permission_scope_id_seq SET SCHEMA permission_service;
ALTER SEQUENCE IF EXISTS public.authz_audit_log_id_seq SET SCHEMA permission_service;

ALTER TABLE IF EXISTS public.users SET SCHEMA user_service;
ALTER TABLE IF EXISTS public.user_audit_events SET SCHEMA user_service;
ALTER TABLE IF EXISTS public.user_notification_preferences SET SCHEMA user_service;
ALTER TABLE IF EXISTS public.user_flyway_history SET SCHEMA user_service;
ALTER SEQUENCE IF EXISTS public.users_id_seq SET SCHEMA user_service;
ALTER SEQUENCE IF EXISTS public.user_audit_events_id_seq SET SCHEMA user_service;
ALTER SEQUENCE IF EXISTS public.user_notification_preferences_id_seq SET SCHEMA user_service;

COMMIT;

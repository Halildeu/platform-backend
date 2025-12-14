-- Additional indexes to support search/advanced filter performance
create index if not exists idx_users_name_lower on users ((lower(name)));
create index if not exists idx_users_role_upper on users ((upper(role)));
create index if not exists idx_users_enabled on users (enabled);
create index if not exists idx_users_create_date on users (create_date);
create index if not exists idx_users_last_login on users (last_login);
create index if not exists idx_users_session_timeout on users (session_timeout_minutes);

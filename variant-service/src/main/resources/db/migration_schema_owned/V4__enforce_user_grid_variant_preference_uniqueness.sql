CREATE SCHEMA IF NOT EXISTS variant_service;
SET search_path TO variant_service, public;

create unique index if not exists idx_user_grid_variant_pref_default
    on user_grid_variant_preferences (user_id, grid_id)
    where is_default = true;

create unique index if not exists idx_user_grid_variant_pref_selected
    on user_grid_variant_preferences (user_id, grid_id)
    where is_selected = true;

create index if not exists idx_user_grid_variant_pref_variant
    on user_grid_variant_preferences (variant_id);

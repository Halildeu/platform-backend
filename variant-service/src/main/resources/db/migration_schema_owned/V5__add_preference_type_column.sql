CREATE SCHEMA IF NOT EXISTS variant_service;
SET search_path TO variant_service, public;

ALTER TABLE user_grid_variant_preferences
    ADD COLUMN IF NOT EXISTS preference_type varchar(64);

UPDATE user_grid_variant_preferences pref
SET preference_type = CASE
    WHEN v.is_global THEN 'GLOBAL_DEFAULT_OVERRIDE'
    ELSE 'PERSONAL_DEFAULT'
END
FROM user_grid_variants v
WHERE pref.variant_id = v.id;

ALTER TABLE user_grid_variant_preferences
    ALTER COLUMN preference_type SET NOT NULL;

ALTER TABLE user_grid_variant_preferences
    DROP CONSTRAINT IF EXISTS user_grid_variant_preferences_user_id_variant_id_key;

ALTER TABLE user_grid_variant_preferences
    DROP CONSTRAINT IF EXISTS uk_user_grid_pref_user_grid_type;

ALTER TABLE user_grid_variant_preferences
    ADD CONSTRAINT uk_user_grid_pref_user_grid_type
        UNIQUE (user_id, grid_id, preference_type);

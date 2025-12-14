create table if not exists user_grid_variant_preferences
(
    id          uuid primary key,
    user_id     bigint       not null,
    grid_id     varchar(128) not null,
    variant_id  uuid         not null references user_grid_variants (id) on delete cascade,
    is_default  boolean      not null default false,
    is_selected boolean      not null default false,
    created_at  timestamp    not null default now(),
    updated_at  timestamp    not null default now(),
    unique (user_id, variant_id)
);

create index if not exists idx_user_grid_variant_preferences_user_grid
    on user_grid_variant_preferences (user_id, grid_id);

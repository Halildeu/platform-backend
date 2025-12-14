create table if not exists user_grid_variants
(
    id                uuid primary key,
    user_id           bigint       not null,
    grid_id           varchar(128) not null,
    name              varchar(128) not null,
    is_default        boolean      not null default false,
    is_global         boolean      not null default false,
    is_global_default boolean      not null default false,
    state_json        text         not null,
    schema_version    integer      not null default 1,
    is_compatible     boolean      not null default true,
    sort_order        integer      not null default 0,
    created_at        timestamp    not null default now(),
    updated_at        timestamp    not null default now(),
    unique (user_id, grid_id, name)
);

create index if not exists idx_user_grid_variants_grid_user
    on user_grid_variants (grid_id, user_id);

create index if not exists idx_user_grid_variants_global
    on user_grid_variants (grid_id)
    where is_global;

create index if not exists idx_user_grid_variants_sort
    on user_grid_variants (grid_id, sort_order);

CREATE SCHEMA IF NOT EXISTS variant_service;
SET search_path TO variant_service, public;

create table if not exists themes
(
    id             uuid primary key,
    name           varchar(128)   not null,
    type           varchar(16)    not null,
    base_theme_id  uuid,
    appearance     varchar(64)    not null,
    surface_tone   varchar(64),
    density        varchar(32),
    radius         varchar(32),
    elevation      varchar(32),
    motion         varchar(32),
    is_global      boolean        not null default false,
    owner_user_id  varchar(128),
    version        varchar(32),
    published_at   timestamp,
    active_flag    boolean,
    visibility     varchar(64),
    created_at     timestamp      not null default now(),
    updated_at     timestamp
);

create table if not exists theme_overrides
(
    theme_id     uuid         not null references themes (id) on delete cascade,
    registry_key varchar(128) not null,
    value        text         not null,
    primary key (theme_id, registry_key)
);

create table if not exists theme_registry
(
    id             uuid primary key,
    key            varchar(128) not null unique,
    label          varchar(256) not null,
    group_name     varchar(64)  not null,
    control_type   varchar(16)  not null,
    editable_by    varchar(16)  not null,
    description    text,
    default_source varchar(256)
);

create table if not exists theme_registry_css_vars
(
    registry_id uuid         not null references theme_registry (id) on delete cascade,
    css_var     varchar(128) not null
);


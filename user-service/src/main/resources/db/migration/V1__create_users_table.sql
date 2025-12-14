create table if not exists users
(
    id                      bigserial primary key,
    name                    varchar(255) not null,
    email                   varchar(255) not null unique,
    password                varchar(255) not null,
    role                    varchar(64)  not null default 'USER',
    enabled                 boolean      not null default true,
    create_date             timestamp    not null default now(),
    last_login              timestamp,
    session_timeout_minutes integer      not null default 15
);

create index if not exists idx_users_email on users (lower(email));

alter table if exists users
    add column if not exists version integer not null default 0;

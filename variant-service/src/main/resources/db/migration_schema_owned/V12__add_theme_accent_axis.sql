CREATE SCHEMA IF NOT EXISTS variant_service;
SET search_path TO variant_service, public;

alter table if exists themes
    add column if not exists accent varchar(32);

update themes
set accent = 'neutral'
where accent is null;


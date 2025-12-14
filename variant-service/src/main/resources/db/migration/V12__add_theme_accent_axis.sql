alter table if exists themes
    add column if not exists accent varchar(32);

update themes
set accent = 'neutral'
where accent is null;


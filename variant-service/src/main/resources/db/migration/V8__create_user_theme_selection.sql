create table if not exists user_theme_selections
(
    user_id  varchar(128) not null primary key,
    theme_id uuid references themes (id) on delete set null
);


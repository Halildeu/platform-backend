create table if not exists user_notification_preferences
(
    id         bigserial primary key,
    user_id    bigint      not null references users (id) on delete cascade,
    channel    varchar(64) not null,
    enabled    boolean     not null,
    frequency  varchar(32),
    updated_at timestamp   not null default now(),
    constraint uq_user_notification_preferences_user_channel unique (user_id, channel)
);

create index if not exists idx_user_notification_preferences_user_id on user_notification_preferences (user_id);


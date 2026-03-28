alter table if exists user_notification_preferences
    add column if not exists version integer not null default 0;

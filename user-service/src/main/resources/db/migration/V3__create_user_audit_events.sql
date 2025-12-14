create table if not exists user_audit_events
(
    id               bigserial primary key,
    event_type       varchar(100) not null,
    performed_by     bigint,
    details          varchar(2000),
    target_user_id   bigint,
    occurred_at      timestamp not null default now()
);

create index if not exists idx_user_audit_events_event_type on user_audit_events (event_type);
create index if not exists idx_user_audit_events_target_user on user_audit_events (target_user_id);

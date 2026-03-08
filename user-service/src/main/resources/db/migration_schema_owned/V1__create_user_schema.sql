CREATE SCHEMA IF NOT EXISTS user_service;
SET search_path TO user_service, public;

CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    role VARCHAR(64) NOT NULL DEFAULT 'USER',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    create_date TIMESTAMP NOT NULL DEFAULT NOW(),
    last_login TIMESTAMP,
    session_timeout_minutes INTEGER NOT NULL DEFAULT 15,
    company_id BIGINT
);

CREATE INDEX IF NOT EXISTS idx_users_email ON users (lower(email));
CREATE INDEX IF NOT EXISTS idx_users_name_lower ON users (lower(name));
CREATE INDEX IF NOT EXISTS idx_users_role_upper ON users (upper(role));
CREATE INDEX IF NOT EXISTS idx_users_enabled ON users (enabled);
CREATE INDEX IF NOT EXISTS idx_users_create_date ON users (create_date);
CREATE INDEX IF NOT EXISTS idx_users_last_login ON users (last_login);
CREATE INDEX IF NOT EXISTS idx_users_session_timeout ON users (session_timeout_minutes);
CREATE INDEX IF NOT EXISTS idx_users_company_id ON users (company_id);

CREATE TABLE IF NOT EXISTS user_audit_events (
    id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    performed_by BIGINT,
    details VARCHAR(2000),
    target_user_id BIGINT,
    occurred_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_user_audit_events_event_type ON user_audit_events (event_type);
CREATE INDEX IF NOT EXISTS idx_user_audit_events_target_user ON user_audit_events (target_user_id);

CREATE TABLE IF NOT EXISTS user_notification_preferences (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    channel VARCHAR(64) NOT NULL,
    enabled BOOLEAN NOT NULL,
    frequency VARCHAR(32),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_user_notification_preferences_user_channel UNIQUE (user_id, channel)
);

CREATE INDEX IF NOT EXISTS idx_user_notification_preferences_user_id
    ON user_notification_preferences (user_id);

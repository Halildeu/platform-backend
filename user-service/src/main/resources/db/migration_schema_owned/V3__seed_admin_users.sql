CREATE SCHEMA IF NOT EXISTS user_service;
SET search_path TO user_service, public;

INSERT INTO users (name, email, password, role, enabled, session_timeout_minutes)
VALUES (
    'Admin User',
    'admin@example.com',
    '$2b$10$E8MbduvTLsJiFy.236vSp.ckVePkZxOXpQaHbdxWjunvMyujY5HYS',
    'ADMIN',
    TRUE,
    15
)
ON CONFLICT (email) DO UPDATE
    SET name = EXCLUDED.name,
        role = EXCLUDED.role,
        enabled = EXCLUDED.enabled;

INSERT INTO users (name, email, password, role, enabled, session_timeout_minutes)
VALUES (
    'Admin 1',
    'admin1@example.com',
    '$2b$10$E8MbduvTLsJiFy.236vSp.ckVePkZxOXpQaHbdxWjunvMyujY5HYS',
    'ADMIN',
    TRUE,
    15
)
ON CONFLICT (email) DO UPDATE
    SET name = EXCLUDED.name,
        role = EXCLUDED.role,
        enabled = EXCLUDED.enabled;

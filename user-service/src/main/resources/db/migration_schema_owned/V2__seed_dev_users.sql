CREATE SCHEMA IF NOT EXISTS user_service;
SET search_path TO user_service, public;

INSERT INTO users (name, email, password, role, enabled, session_timeout_minutes)
SELECT
    'User ' || gs AS name,
    'user' || gs || '@example.com' AS email,
    'seed' AS password,
    'USER' AS role,
    TRUE AS enabled,
    15 AS session_timeout_minutes
FROM generate_series(1, 1200) AS gs
ON CONFLICT (email) DO NOTHING;

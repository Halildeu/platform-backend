-- Seed 1200 standard users (role=USER, enabled=true)
-- Uses generate_series and ignores existing emails via ON CONFLICT DO NOTHING

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


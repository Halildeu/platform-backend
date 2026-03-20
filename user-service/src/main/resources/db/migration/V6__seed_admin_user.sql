-- Seed admin user (upsert)
INSERT INTO users (name, email, password, role, enabled, session_timeout_minutes)
VALUES (
    'Admin User',
    'admin@example.com',
    '$2b$10$E8MbduvTLsJiFy.236vSp.ckVePkZxOXpQaHbdxWjunvMyujY5HYS', -- bcrypt("admin1234")
    'ADMIN',
    TRUE,
    15
)
ON CONFLICT (email) DO UPDATE
    SET name = EXCLUDED.name,
        role = EXCLUDED.role,
        enabled = EXCLUDED.enabled;

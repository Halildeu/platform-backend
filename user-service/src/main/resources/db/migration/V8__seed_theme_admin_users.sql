-- Ensure admin@example.com and admin1@example.com exist as ADMIN users.
-- Role assignments (user_role_assignments) are managed by permission-service
-- in its own schema — user-service only manages the users table.

-- 1) Upsert primary admin user (admin@example.com) as ADMIN
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
    SET name   = EXCLUDED.name,
        role   = EXCLUDED.role,
        enabled = EXCLUDED.enabled;

-- 2) Upsert secondary admin user (admin1@example.com) as ADMIN
INSERT INTO users (name, email, password, role, enabled, session_timeout_minutes)
VALUES (
    'Admin 1',
    'admin1@example.com',
    '$2b$10$E8MbduvTLsJiFy.236vSp.ckVePkZxOXpQaHbdxWjunvMyujY5HYS', -- bcrypt("admin1234")
    'ADMIN',
    TRUE,
    15
)
ON CONFLICT (email) DO UPDATE
    SET name   = EXCLUDED.name,
        role   = EXCLUDED.role,
        enabled = EXCLUDED.enabled;


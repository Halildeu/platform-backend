-- Ensure admin@example.com and admin1@example.com have ADMIN application role
-- and a corresponding ACTIVE assignment in user_role_assignments.
-- This aligns backend role mapping with THEME_ADMIN permission wiring
-- (ADMIN role -> THEME_ADMIN) in permission-service.

-- 1) Upsert primary admin user (admin@example.com) as ADMIN
WITH upsert_admin AS (
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
            enabled = EXCLUDED.enabled
    RETURNING id
),
resolved_admin AS (
    SELECT id FROM upsert_admin
    UNION
    SELECT id FROM users WHERE email = 'admin@example.com'
),
admin_role AS (
    SELECT id FROM roles WHERE name = 'ADMIN' LIMIT 1
)
INSERT INTO user_role_assignments (
    active,
    assigned_at,
    user_id,
    role_id,
    assigned_by
)
SELECT
    TRUE,
    now(),
    ra.id,
    ar.id,
    NULL
FROM resolved_admin ra
JOIN admin_role ar ON TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM user_role_assignments
    WHERE user_id = ra.id AND role_id = ar.id AND active = TRUE
);

-- 2) Upsert secondary admin user (admin1@example.com) as ADMIN
WITH upsert_admin1 AS (
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
            enabled = EXCLUDED.enabled
    RETURNING id
),
resolved_admin1 AS (
    SELECT id FROM upsert_admin1
    UNION
    SELECT id FROM users WHERE email = 'admin1@example.com'
),
admin_role2 AS (
    SELECT id FROM roles WHERE name = 'ADMIN' LIMIT 1
)
INSERT INTO user_role_assignments (
    active,
    assigned_at,
    user_id,
    role_id,
    assigned_by
)
SELECT
    TRUE,
    now(),
    ra1.id,
    ar2.id,
    NULL
FROM resolved_admin1 ra1
JOIN admin_role2 ar2 ON TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM user_role_assignments
    WHERE user_id = ra1.id AND role_id = ar2.id AND active = TRUE
);


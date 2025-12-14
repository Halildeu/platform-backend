WITH upsert_user AS (
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
            enabled = EXCLUDED.enabled
    RETURNING id
),
target_user AS (
    SELECT id FROM upsert_user
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
    tu.id,
    ar.id,
    NULL
FROM target_user tu
JOIN admin_role ar ON TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM user_role_assignments
    WHERE user_id = tu.id AND role_id = ar.id AND active = TRUE
);

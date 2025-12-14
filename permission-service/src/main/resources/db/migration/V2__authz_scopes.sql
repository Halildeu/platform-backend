-- Scope'lu yetkilendirme altyapısı ve mevcut modeller için şema
-- Not: IF NOT EXISTS kullanımı mevcut veriyle çakışmayı önler, yeni kurulumlarda tabloyu oluşturur.

-- Permission sözlüğü
CREATE TABLE IF NOT EXISTS permissions (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(150) NOT NULL,
    description VARCHAR(255),
    module_name VARCHAR(150),
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_permissions_code ON permissions(code);

-- Rol kataloğu
CREATE TABLE IF NOT EXISTS roles (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_roles_name ON roles(name);

-- Rol → permission ilişkisi
CREATE TABLE IF NOT EXISTS role_permissions (
    id BIGSERIAL PRIMARY KEY,
    role_id BIGINT NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission_id BIGINT NOT NULL REFERENCES permissions(id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_role_permissions_role_permission ON role_permissions(role_id, permission_id);

-- Kullanıcı → rol ataması (mevcut scope alanlarıyla)
CREATE TABLE IF NOT EXISTS user_role_assignments (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    company_id BIGINT,
    project_id BIGINT,
    warehouse_id BIGINT,
    role_id BIGINT NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    assigned_at TIMESTAMP NOT NULL DEFAULT NOW(),
    assigned_by BIGINT,
    revoked_at TIMESTAMP,
    revoked_by BIGINT
);
CREATE INDEX IF NOT EXISTS idx_user_role_assignments_user_company ON user_role_assignments(user_id, company_id);
CREATE INDEX IF NOT EXISTS idx_user_role_assignments_scope ON user_role_assignments(project_id, warehouse_id);

-- Permission değişiklikleri için audit tablosu
CREATE TABLE IF NOT EXISTS permission_audit_events (
    id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    performed_by BIGINT,
    details VARCHAR(2000),
    user_email VARCHAR(255),
    service VARCHAR(255),
    level VARCHAR(50),
    action VARCHAR(100),
    correlation_id VARCHAR(255),
    metadata TEXT,
    before_state TEXT,
    after_state TEXT,
    occurred_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_permission_audit_events_type ON permission_audit_events(event_type);
CREATE INDEX IF NOT EXISTS idx_permission_audit_events_user ON permission_audit_events(user_email);
CREATE INDEX IF NOT EXISTS idx_permission_audit_events_service ON permission_audit_events(service);

-- Scope sözlüğü
CREATE TABLE IF NOT EXISTS scopes (
    id BIGSERIAL PRIMARY KEY,
    scope_type VARCHAR(50) NOT NULL,
    ref_id BIGINT NOT NULL,
    parent_scope_id BIGINT,
    description VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP,
    CONSTRAINT fk_scopes_parent FOREIGN KEY (parent_scope_id) REFERENCES scopes(id) ON DELETE SET NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_scopes_type_ref ON scopes(scope_type, ref_id);

-- Kullanıcı → permission → scope ilişkisi
CREATE TABLE IF NOT EXISTS user_permission_scope (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    scope_id BIGINT NOT NULL REFERENCES scopes(id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_permission_scope ON user_permission_scope(user_id, permission_id, scope_id);
CREATE INDEX IF NOT EXISTS idx_user_permission_scope_user ON user_permission_scope(user_id);

-- AuthorizationContext audit trail
CREATE TABLE IF NOT EXISTS authz_audit_log (
    id BIGSERIAL PRIMARY KEY,
    actor_user_id BIGINT,
    action VARCHAR(100) NOT NULL,
    target_type VARCHAR(100),
    target_id VARCHAR(100),
    old_value TEXT,
    new_value TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_authz_audit_target ON authz_audit_log(target_type, target_id);

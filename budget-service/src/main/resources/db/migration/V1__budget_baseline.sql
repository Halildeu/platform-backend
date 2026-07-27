CREATE TABLE budget_plans (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    company_id BIGINT NOT NULL,
    fiscal_year INTEGER NOT NULL,
    base_currency VARCHAR(3) NOT NULL,
    created_by VARCHAR(200) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_budget_plan_scope UNIQUE (tenant_id, company_id, fiscal_year),
    CONSTRAINT ck_budget_fiscal_year CHECK (fiscal_year BETWEEN 2000 AND 2200)
);

CREATE TABLE budget_versions (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL REFERENCES budget_plans(id),
    tenant_id VARCHAR(128) NOT NULL,
    version_no INTEGER NOT NULL,
    status VARCHAR(24) NOT NULL,
    submitted_by VARCHAR(200),
    submitted_at TIMESTAMP WITH TIME ZONE,
    approved_by VARCHAR(200),
    approved_at TIMESTAMP WITH TIME ZONE,
    created_by VARCHAR(200) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_budget_version_no UNIQUE (plan_id, version_no),
    CONSTRAINT ck_budget_version_status
        CHECK (status IN ('DRAFT','SUBMITTED','APPROVED','REJECTED','SUPERSEDED')),
    CONSTRAINT ck_budget_two_person
        CHECK (approved_by IS NULL OR submitted_by IS NULL OR approved_by <> submitted_by)
);

CREATE TABLE budget_lines (
    id UUID PRIMARY KEY,
    version_id UUID NOT NULL REFERENCES budget_versions(id),
    tenant_id VARCHAR(128) NOT NULL,
    period_start DATE NOT NULL,
    account_code VARCHAR(80) NOT NULL,
    cost_center_code VARCHAR(80),
    project_code VARCHAR(80),
    department_code VARCHAR(80),
    branch_code VARCHAR(80),
    direction VARCHAR(12) NOT NULL,
    planned_amount DECIMAL(19,4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    description VARCHAR(500),
    CONSTRAINT ck_budget_line_direction CHECK (direction IN ('EXPENSE','INCOME')),
    CONSTRAINT ck_budget_line_amount CHECK (planned_amount >= 0),
    CONSTRAINT uq_budget_line_grain UNIQUE (
        version_id, period_start, account_code, cost_center_code,
        project_code, department_code, branch_code, direction
    )
);

CREATE TABLE source_type_registry (
    action_type INTEGER NOT NULL,
    registry_version INTEGER NOT NULL,
    source_family VARCHAR(40) NOT NULL,
    source_table VARCHAR(120),
    classification VARCHAR(80) NOT NULL,
    evidence_ref VARCHAR(500) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (action_type, registry_version)
);

INSERT INTO source_type_registry (
    action_type, registry_version, source_family, source_table, classification, evidence_ref
) VALUES
    (13, 1, 'MANUAL_JOURNAL', NULL, 'manual-journal', 'live-company35-2026'),
    (23, 1, 'BANK', 'BANK_ACTIONS', 'bank-outgoing', 'live-company35-2026'),
    (24, 1, 'BANK', 'BANK_ACTIONS', 'bank-incoming', 'live-company35-2026'),
    (25, 1, 'BANK', 'BANK_ACTIONS', 'bank-transfer', 'live-company35-2026'),
    (48, 1, 'INVOICE', 'INVOICE', 'invoice', 'live-company35-2026'),
    (111, 1, 'STOCK', 'STOCK_FIS', 'stock-slip', 'live-company35-2026'),
    (118, 1, 'STOCK', 'STOCK_FIS', 'stock-slip', 'live-company35-2026'),
    (120, 1, 'EXPENSE_PLAN', 'EXPENSE_ITEM_PLANS', 'expense-plan', 'live-company35-2026'),
    (121, 1, 'EXPENSE_PLAN', 'EXPENSE_ITEM_PLANS', 'expense-plan', 'live-company35-2026');

CREATE TABLE actual_snapshots (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    company_id BIGINT NOT NULL,
    fiscal_year INTEGER NOT NULL,
    period_start DATE NOT NULL,
    journal_card_id BIGINT NOT NULL,
    journal_row_id BIGINT NOT NULL,
    action_type INTEGER,
    action_id BIGINT,
    resolution_status VARCHAR(24) NOT NULL,
    direction VARCHAR(12) NOT NULL,
    normalized_amount DECIMAL(19,4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    source_hash VARCHAR(64) NOT NULL,
    source_updated_at TIMESTAMP WITH TIME ZONE,
    is_cancelled BOOLEAN NOT NULL DEFAULT FALSE,
    synced_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_actual_journal_row UNIQUE (tenant_id, company_id, journal_row_id),
    CONSTRAINT ck_actual_resolution CHECK (
        resolution_status IN ('EXACT_LINE','HEADER_ONLY','PARTIAL','UNRESOLVED','MANUAL_JOURNAL')
    ),
    CONSTRAINT ck_actual_direction CHECK (direction IN ('EXPENSE','INCOME'))
);

CREATE TABLE actual_allocations (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    actual_snapshot_id UUID NOT NULL REFERENCES actual_snapshots(id),
    budget_line_id UUID NOT NULL REFERENCES budget_lines(id),
    allocated_amount DECIMAL(19,4) NOT NULL,
    created_by VARCHAR(200) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_actual_budget_allocation UNIQUE (actual_snapshot_id, budget_line_id),
    CONSTRAINT ck_actual_allocation_nonzero CHECK (allocated_amount <> 0)
);

CREATE TABLE budget_commitments (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    company_id BIGINT NOT NULL,
    fiscal_year INTEGER NOT NULL,
    period_start DATE NOT NULL,
    budget_line_id UUID REFERENCES budget_lines(id),
    source_system VARCHAR(60) NOT NULL,
    source_id VARCHAR(160) NOT NULL,
    status VARCHAR(20) NOT NULL,
    amount DECIMAL(19,4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    source_hash VARCHAR(64) NOT NULL,
    CONSTRAINT uq_budget_commitment_source UNIQUE (tenant_id, company_id, source_system, source_id),
    CONSTRAINT ck_budget_commitment_status CHECK (status IN ('OPEN','CONSUMED','CANCELLED'))
);

CREATE TABLE budget_forecasts (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    version_id UUID NOT NULL REFERENCES budget_versions(id),
    budget_line_id UUID NOT NULL REFERENCES budget_lines(id),
    period_start DATE NOT NULL,
    etc_amount DECIMAL(19,4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    created_by VARCHAR(200) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_budget_forecast_grain UNIQUE (version_id, budget_line_id, period_start)
);

CREATE TABLE budget_sync_checkpoints (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    company_id BIGINT NOT NULL,
    fiscal_year INTEGER NOT NULL,
    source_system VARCHAR(60) NOT NULL,
    watermark_value VARCHAR(200) NOT NULL,
    last_source_hash VARCHAR(64),
    last_success_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_budget_sync_checkpoint UNIQUE (tenant_id, company_id, fiscal_year, source_system)
);

CREATE TABLE budget_reconciliation_runs (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    company_id BIGINT NOT NULL,
    fiscal_year INTEGER NOT NULL,
    period_start DATE NOT NULL,
    source_amount DECIMAL(19,4) NOT NULL,
    snapshot_amount DECIMAL(19,4) NOT NULL,
    difference_amount DECIMAL(19,4) NOT NULL,
    status VARCHAR(20) NOT NULL,
    executed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_budget_reconciliation_status CHECK (status IN ('MATCHED','DIFFERENCE','BLOCKED'))
);

CREATE TABLE budget_audit_events (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    aggregate_type VARCHAR(60) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    actor_id VARCHAR(200) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX ix_budget_versions_plan ON budget_versions(plan_id, version_no DESC);
CREATE INDEX ix_budget_lines_version_period ON budget_lines(version_id, period_start);
CREATE INDEX ix_actual_scope_period ON actual_snapshots(tenant_id, company_id, fiscal_year, period_start);
CREATE INDEX ix_actual_source ON actual_snapshots(action_type, action_id);
CREATE INDEX ix_commitment_scope ON budget_commitments(tenant_id, company_id, fiscal_year, status);
CREATE INDEX ix_audit_aggregate ON budget_audit_events(tenant_id, aggregate_type, aggregate_id, created_at);

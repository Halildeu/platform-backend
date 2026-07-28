CREATE TABLE budget_project_bindings (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    company_id BIGINT NOT NULL,
    platform_project_ref VARCHAR(160) NOT NULL,
    source_system VARCHAR(60) NOT NULL,
    external_company_no BIGINT NOT NULL,
    external_project_id BIGINT NOT NULL,
    external_project_code VARCHAR(80),
    created_by VARCHAR(200) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    verified_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_budget_project_source UNIQUE (
        tenant_id, company_id, source_system, external_company_no, external_project_id
    ),
    CONSTRAINT uq_budget_project_ref UNIQUE (
        tenant_id, company_id, platform_project_ref, source_system
    )
);

CREATE TABLE cost_rule_sets (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    company_id BIGINT NOT NULL,
    version_no INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_by VARCHAR(200) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    activated_by VARCHAR(200),
    activated_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_cost_rule_set_version UNIQUE (tenant_id, company_id, version_no),
    CONSTRAINT ck_cost_rule_set_status CHECK (status IN ('DRAFT','ACTIVE','RETIRED')),
    CONSTRAINT ck_cost_rule_set_activation CHECK (
        status <> 'ACTIVE' OR (activated_by IS NOT NULL AND activated_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_active_cost_rule_set
    ON cost_rule_sets (tenant_id, company_id)
    WHERE status = 'ACTIVE';

CREATE TABLE cost_account_rules (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    rule_set_id UUID NOT NULL REFERENCES cost_rule_sets(id),
    priority INTEGER NOT NULL,
    account_prefix VARCHAR(80) NOT NULL,
    debit_treatment VARCHAR(32) NOT NULL,
    credit_treatment VARCHAR(32) NOT NULL,
    document_type VARCHAR(40),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_cost_rule_priority UNIQUE (rule_set_id, priority),
    CONSTRAINT ck_cost_rule_priority CHECK (priority BETWEEN 1 AND 10000),
    CONSTRAINT ck_cost_rule_debit_treatment CHECK (
        debit_treatment IN (
            'INCLUDE_COST','INCLUDE_NEGATIVE_COST','EXCLUDE_COUNTERPART',
            'EXCLUDE_TRANSFER','REQUIRES_REVIEW'
        )
    ),
    CONSTRAINT ck_cost_rule_credit_treatment CHECK (
        credit_treatment IN (
            'INCLUDE_COST','INCLUDE_NEGATIVE_COST','EXCLUDE_COUNTERPART',
            'EXCLUDE_TRANSFER','REQUIRES_REVIEW'
        )
    )
);

CREATE TABLE actual_sync_batches (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    company_id BIGINT NOT NULL,
    project_binding_id UUID NOT NULL REFERENCES budget_project_bindings(id),
    source_system VARCHAR(60) NOT NULL,
    window_from DATE NOT NULL,
    window_to DATE NOT NULL,
    status VARCHAR(20) NOT NULL,
    source_row_count INTEGER NOT NULL DEFAULT 0,
    changed_row_count INTEGER NOT NULL DEFAULT 0,
    tombstone_row_count INTEGER NOT NULL DEFAULT 0,
    source_amount DECIMAL(19,4) NOT NULL DEFAULT 0,
    snapshot_amount DECIMAL(19,4) NOT NULL DEFAULT 0,
    source_fingerprint VARCHAR(64),
    started_by VARCHAR(200) NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at TIMESTAMP WITH TIME ZONE,
    failure_code VARCHAR(80),
    CONSTRAINT ck_actual_sync_window CHECK (window_to >= window_from),
    CONSTRAINT ck_actual_sync_status CHECK (
        status IN ('RUNNING','MATCHED','DIFFERENCE','BLOCKED')
    )
);

ALTER TABLE budget_plans
    ADD COLUMN project_binding_id UUID REFERENCES budget_project_bindings(id);

ALTER TABLE budget_plans
    DROP CONSTRAINT uq_budget_plan_scope;

CREATE UNIQUE INDEX uq_budget_plan_project
    ON budget_plans (tenant_id, company_id, project_binding_id)
    WHERE project_binding_id IS NOT NULL;

CREATE UNIQUE INDEX uq_budget_plan_legacy_scope
    ON budget_plans (tenant_id, company_id, fiscal_year)
    WHERE project_binding_id IS NULL;

ALTER TABLE actual_snapshots
    ADD COLUMN project_binding_id UUID REFERENCES budget_project_bindings(id),
    ADD COLUMN source_system VARCHAR(60) NOT NULL DEFAULT 'WORKCUBE',
    ADD COLUMN source_partition VARCHAR(128) NOT NULL DEFAULT 'legacy',
    ADD COLUMN account_code VARCHAR(80),
    ADD COLUMN debit_credit VARCHAR(8) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN cost_treatment VARCHAR(32) NOT NULL DEFAULT 'REQUIRES_REVIEW',
    ADD COLUMN cost_rule_version INTEGER,
    ADD COLUMN document_type VARCHAR(40),
    ADD COLUMN document_no VARCHAR(160),
    ADD COLUMN sync_batch_id UUID REFERENCES actual_sync_batches(id),
    ADD COLUMN first_seen_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN last_seen_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE actual_snapshots
    DROP CONSTRAINT uq_actual_journal_row;

ALTER TABLE actual_snapshots
    ADD CONSTRAINT ck_actual_debit_credit
        CHECK (debit_credit IN ('DEBIT','CREDIT','UNKNOWN')),
    ADD CONSTRAINT ck_actual_cost_treatment
        CHECK (
            cost_treatment IN (
                'INCLUDE_COST','INCLUDE_NEGATIVE_COST','EXCLUDE_COUNTERPART',
                'EXCLUDE_TRANSFER','REQUIRES_REVIEW'
            )
        );

CREATE UNIQUE INDEX uq_actual_source_grain
    ON actual_snapshots (
        tenant_id, company_id, source_system, source_partition, journal_row_id
    );

CREATE INDEX ix_actual_project_period
    ON actual_snapshots (
        tenant_id, project_binding_id, period_start, account_code
    );

CREATE TABLE actual_snapshot_versions (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    snapshot_id UUID NOT NULL REFERENCES actual_snapshots(id),
    version_no INTEGER NOT NULL,
    sync_batch_id UUID NOT NULL REFERENCES actual_sync_batches(id),
    recorded_reason VARCHAR(20) NOT NULL,
    source_hash VARCHAR(64) NOT NULL,
    normalized_amount DECIMAL(19,4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    debit_credit VARCHAR(8) NOT NULL,
    account_code VARCHAR(80),
    cost_treatment VARCHAR(32) NOT NULL,
    cost_rule_version INTEGER,
    resolution_status VARCHAR(24) NOT NULL,
    is_cancelled BOOLEAN NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_actual_snapshot_version UNIQUE (snapshot_id, version_no),
    CONSTRAINT ck_actual_version_reason CHECK (
        recorded_reason IN ('FIRST_SEEN','SOURCE_CHANGED','TOMBSTONED','RECLASSIFIED')
    )
);

CREATE TABLE actual_sync_checkpoints (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    company_id BIGINT NOT NULL,
    source_system VARCHAR(60) NOT NULL,
    source_scope_key VARCHAR(240) NOT NULL,
    window_from DATE NOT NULL,
    window_to DATE NOT NULL,
    source_fingerprint VARCHAR(64) NOT NULL,
    last_batch_id UUID NOT NULL REFERENCES actual_sync_batches(id),
    last_success_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_actual_sync_checkpoint UNIQUE (
        tenant_id, company_id, source_system, source_scope_key, window_from, window_to
    )
);

ALTER TABLE budget_reconciliation_runs
    ADD COLUMN project_binding_id UUID REFERENCES budget_project_bindings(id),
    ADD COLUMN sync_batch_id UUID REFERENCES actual_sync_batches(id),
    ADD COLUMN window_from DATE,
    ADD COLUMN window_to DATE,
    ADD COLUMN source_row_count INTEGER,
    ADD COLUMN snapshot_row_count INTEGER;

CREATE INDEX ix_budget_reconciliation_project
    ON budget_reconciliation_runs (
        tenant_id, project_binding_id, executed_at DESC
    );

CREATE OR REPLACE FUNCTION actual_snapshot_version_append_only_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'actual snapshot versions are append-only';
END;
$$;

CREATE TRIGGER trg_actual_snapshot_version_append_only
BEFORE UPDATE OR DELETE ON actual_snapshot_versions
FOR EACH ROW EXECUTE FUNCTION actual_snapshot_version_append_only_guard();

ALTER TABLE budget_project_bindings ENABLE ROW LEVEL SECURITY;
ALTER TABLE budget_project_bindings FORCE ROW LEVEL SECURITY;
ALTER TABLE cost_rule_sets ENABLE ROW LEVEL SECURITY;
ALTER TABLE cost_rule_sets FORCE ROW LEVEL SECURITY;
ALTER TABLE cost_account_rules ENABLE ROW LEVEL SECURITY;
ALTER TABLE cost_account_rules FORCE ROW LEVEL SECURITY;
ALTER TABLE actual_sync_batches ENABLE ROW LEVEL SECURITY;
ALTER TABLE actual_sync_batches FORCE ROW LEVEL SECURITY;
ALTER TABLE actual_snapshot_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE actual_snapshot_versions FORCE ROW LEVEL SECURITY;
ALTER TABLE actual_sync_checkpoints ENABLE ROW LEVEL SECURITY;
ALTER TABLE actual_sync_checkpoints FORCE ROW LEVEL SECURITY;

CREATE POLICY budget_project_bindings_tenant_policy ON budget_project_bindings
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''));

CREATE POLICY cost_rule_sets_tenant_policy ON cost_rule_sets
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''));

CREATE POLICY cost_account_rules_tenant_policy ON cost_account_rules
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''));

CREATE POLICY actual_sync_batches_tenant_policy ON actual_sync_batches
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''));

CREATE POLICY actual_snapshot_versions_tenant_policy ON actual_snapshot_versions
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''));

CREATE POLICY actual_sync_checkpoints_tenant_policy ON actual_sync_checkpoints
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''));

CREATE INDEX ix_cost_rule_match
    ON cost_account_rules (tenant_id, rule_set_id, priority, account_prefix);

CREATE INDEX ix_actual_version_snapshot
    ON actual_snapshot_versions (tenant_id, snapshot_id, version_no DESC);

CREATE INDEX ix_actual_sync_scope
    ON actual_sync_batches (
        tenant_id, project_binding_id, window_from, window_to, started_at DESC
    );

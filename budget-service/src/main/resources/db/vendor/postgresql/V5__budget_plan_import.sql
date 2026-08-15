-- #3454: import existing Workcube budget-plan assignments as a versioned
-- workcube-import draft. Mirrors the actuals sync-batch vocabulary (V3/V4):
-- a batch row per import run, explicit skip records instead of silent drops,
-- and RLS on every new table.

ALTER TABLE budget_versions
    ADD COLUMN origin VARCHAR(40) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN origin_batch_id UUID;

ALTER TABLE budget_versions
    ADD CONSTRAINT ck_budget_version_origin
        CHECK (origin IN ('MANUAL', 'WORKCUBE_IMPORT'));

CREATE TABLE budget_plan_import_batches (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    company_id BIGINT NOT NULL,
    fiscal_year INTEGER NOT NULL,
    source_system VARCHAR(60) NOT NULL DEFAULT 'WORKCUBE',
    status VARCHAR(24) NOT NULL,
    plan_id UUID REFERENCES budget_plans(id),
    version_id UUID REFERENCES budget_versions(id),
    fetched_rows INTEGER NOT NULL DEFAULT 0,
    imported_lines INTEGER NOT NULL DEFAULT 0,
    merged_rows INTEGER NOT NULL DEFAULT 0,
    split_rows INTEGER NOT NULL DEFAULT 0,
    scenario_rows INTEGER NOT NULL DEFAULT 0,
    skipped_rows INTEGER NOT NULL DEFAULT 0,
    source_fingerprint VARCHAR(64),
    failure_code VARCHAR(80),
    started_by VARCHAR(200) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    CONSTRAINT ck_plan_import_status
        CHECK (status IN ('RUNNING', 'COMPLETED', 'BLOCKED')),
    CONSTRAINT ck_plan_import_fiscal_year
        CHECK (fiscal_year BETWEEN 2000 AND 2200)
);

CREATE INDEX ix_plan_import_batches_scope
    ON budget_plan_import_batches (tenant_id, company_id, fiscal_year, started_at DESC);

-- Skipped source rows are first-class data, not log lines: the API returns the
-- counts and callers can list exactly which BUDGET_PLAN_ROW ids were excluded
-- and why (unresolved mapping, scenario plan, zero/negative amount).
CREATE TABLE budget_plan_import_skips (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL REFERENCES budget_plan_import_batches(id),
    tenant_id VARCHAR(128) NOT NULL,
    source_budget_plan_row_id BIGINT NOT NULL,
    source_budget_plan_id BIGINT NOT NULL,
    reason VARCHAR(60) NOT NULL,
    detail VARCHAR(500),
    CONSTRAINT ck_plan_import_skip_reason
        CHECK (reason IN (
            'SCENARIO_PLAN',
            'MISSING_ACCOUNT_CODE',
            'MISSING_PERIOD',
            'ZERO_AMOUNT',
            'NEGATIVE_AMOUNT')),
    CONSTRAINT uq_plan_import_skip UNIQUE (batch_id, source_budget_plan_row_id, reason)
);

CREATE INDEX ix_plan_import_skips_batch
    ON budget_plan_import_skips (batch_id);

ALTER TABLE budget_plan_import_batches ENABLE ROW LEVEL SECURITY;
ALTER TABLE budget_plan_import_batches FORCE ROW LEVEL SECURITY;
ALTER TABLE budget_plan_import_skips ENABLE ROW LEVEL SECURITY;
ALTER TABLE budget_plan_import_skips FORCE ROW LEVEL SECURITY;

CREATE POLICY budget_plan_import_batches_tenant_policy ON budget_plan_import_batches
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''));

CREATE POLICY budget_plan_import_skips_tenant_policy ON budget_plan_import_skips
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''));

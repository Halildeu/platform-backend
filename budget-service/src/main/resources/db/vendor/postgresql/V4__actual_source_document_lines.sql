ALTER TABLE actual_snapshots
    ADD COLUMN source_document_external_id BIGINT,
    ADD COLUMN source_document_line_external_id BIGINT;

ALTER TABLE actual_sync_batches
    ADD COLUMN source_document_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN source_line_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN changed_source_line_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN tombstone_source_line_count INTEGER NOT NULL DEFAULT 0;

CREATE INDEX ix_actual_snapshot_source_document
    ON actual_snapshots (
        tenant_id,
        project_binding_id,
        source_partition,
        document_type,
        source_document_external_id
    );

CREATE TABLE actual_source_documents (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    company_id BIGINT NOT NULL,
    project_binding_id UUID NOT NULL REFERENCES budget_project_bindings(id),
    source_system VARCHAR(60) NOT NULL,
    source_partition VARCHAR(128) NOT NULL,
    document_type VARCHAR(40) NOT NULL,
    external_document_id BIGINT NOT NULL,
    document_no VARCHAR(160),
    document_date DATE NOT NULL,
    document_kind VARCHAR(40) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    source_hash VARCHAR(64) NOT NULL,
    is_cancelled BOOLEAN NOT NULL DEFAULT FALSE,
    source_line_total DECIMAL(19,4) NOT NULL DEFAULT 0,
    accounting_cost_total DECIMAL(19,4) NOT NULL DEFAULT 0,
    reconciliation_difference DECIMAL(19,4) NOT NULL DEFAULT 0,
    reconciliation_status VARCHAR(32) NOT NULL DEFAULT 'UNRESOLVED',
    accounting_row_count INTEGER NOT NULL DEFAULT 0,
    sync_batch_id UUID NOT NULL REFERENCES actual_sync_batches(id),
    first_seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
    synced_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_actual_source_document UNIQUE (
        tenant_id,
        company_id,
        project_binding_id,
        source_system,
        source_partition,
        document_type,
        external_document_id
    ),
    CONSTRAINT ck_actual_source_document_kind CHECK (
        document_kind IN (
            'PURCHASE_INVOICE',
            'PURCHASE_RETURN',
            'SALES_INVOICE',
            'SALES_RETURN',
            'OTHER_INVOICE',
            'EXPENSE',
            'STOCK_CONSUMPTION',
            'DEPRECIATION',
            'PAYROLL',
            'TRANSFER',
            'OTHER_SOURCE'
        )
    ),
    CONSTRAINT ck_actual_source_document_reconciliation CHECK (
        reconciliation_status IN (
            'RECONCILED',
            'DIFFERENCE',
            'NO_ACCOUNTING',
            'UNRESOLVED'
        )
    ),
    CONSTRAINT ck_actual_source_document_currency CHECK (
        currency ~ '^[A-Z]{3}$'
    )
);

CREATE TABLE actual_source_lines (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    company_id BIGINT NOT NULL,
    project_binding_id UUID NOT NULL REFERENCES budget_project_bindings(id),
    source_document_id UUID NOT NULL REFERENCES actual_source_documents(id),
    external_line_id BIGINT NOT NULL,
    line_ordinal INTEGER NOT NULL,
    product_name VARCHAR(500),
    line_description TEXT,
    quantity DECIMAL(19,6),
    unit_code VARCHAR(40),
    unit_price DECIMAL(19,6),
    net_amount DECIMAL(19,4) NOT NULL,
    tax_rate DECIMAL(9,4),
    tax_amount DECIMAL(19,4) NOT NULL,
    gross_amount DECIMAL(19,4) NOT NULL,
    cost_basis_amount DECIMAL(19,4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    account_code VARCHAR(80),
    line_match_status VARCHAR(32) NOT NULL DEFAULT 'UNRESOLVED',
    source_hash VARCHAR(64) NOT NULL,
    is_cancelled BOOLEAN NOT NULL DEFAULT FALSE,
    sync_batch_id UUID NOT NULL REFERENCES actual_sync_batches(id),
    first_seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
    synced_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_actual_source_line UNIQUE (
        tenant_id, company_id, source_document_id, external_line_id
    ),
    CONSTRAINT ck_actual_source_line_ordinal CHECK (line_ordinal > 0),
    CONSTRAINT ck_actual_source_line_match CHECK (
        line_match_status IN (
            'EXACT_SOURCE_LINE',
            'RECONCILED',
            'PROPOSED',
            'MANUALLY_CONFIRMED',
            'UNRESOLVED'
        )
    ),
    CONSTRAINT ck_actual_source_line_currency CHECK (
        currency ~ '^[A-Z]{3}$'
    )
);

CREATE TABLE actual_source_line_versions (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    source_line_id UUID NOT NULL REFERENCES actual_source_lines(id),
    version_no INTEGER NOT NULL,
    sync_batch_id UUID NOT NULL REFERENCES actual_sync_batches(id),
    recorded_reason VARCHAR(32) NOT NULL,
    source_hash VARCHAR(64) NOT NULL,
    net_amount DECIMAL(19,4) NOT NULL,
    tax_amount DECIMAL(19,4) NOT NULL,
    gross_amount DECIMAL(19,4) NOT NULL,
    cost_basis_amount DECIMAL(19,4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    line_match_status VARCHAR(32) NOT NULL,
    is_cancelled BOOLEAN NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_actual_source_line_version UNIQUE (source_line_id, version_no),
    CONSTRAINT ck_actual_source_line_version_reason CHECK (
        recorded_reason IN (
            'FIRST_SEEN',
            'SOURCE_CHANGED',
            'TOMBSTONED',
            'RECONCILED',
            'RECONCILIATION_CHANGED',
            'MANUALLY_CONFIRMED'
        )
    )
);

CREATE INDEX ix_actual_source_document_period
    ON actual_source_documents (
        tenant_id, project_binding_id, document_date DESC, external_document_id
    );

CREATE INDEX ix_actual_source_line_document
    ON actual_source_lines (
        tenant_id, source_document_id, line_ordinal, external_line_id
    );

CREATE INDEX ix_actual_source_line_period
    ON actual_source_lines (
        tenant_id, project_binding_id, synced_at DESC
    );

CREATE INDEX ix_actual_source_line_version
    ON actual_source_line_versions (
        tenant_id, source_line_id, version_no DESC
    );

CREATE OR REPLACE FUNCTION actual_source_line_version_append_only_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'actual source line versions are append-only';
END;
$$;

CREATE TRIGGER trg_actual_source_line_version_append_only
BEFORE UPDATE OR DELETE ON actual_source_line_versions
FOR EACH ROW EXECUTE FUNCTION actual_source_line_version_append_only_guard();

ALTER TABLE actual_source_documents ENABLE ROW LEVEL SECURITY;
ALTER TABLE actual_source_documents FORCE ROW LEVEL SECURITY;
ALTER TABLE actual_source_lines ENABLE ROW LEVEL SECURITY;
ALTER TABLE actual_source_lines FORCE ROW LEVEL SECURITY;
ALTER TABLE actual_source_line_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE actual_source_line_versions FORCE ROW LEVEL SECURITY;

CREATE POLICY actual_source_documents_tenant_policy ON actual_source_documents
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''));

CREATE POLICY actual_source_lines_tenant_policy ON actual_source_lines
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''));

CREATE POLICY actual_source_line_versions_tenant_policy ON actual_source_line_versions
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''));

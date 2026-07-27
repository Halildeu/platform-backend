CREATE OR REPLACE FUNCTION budget_version_transition_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status = 'APPROVED' THEN
        RAISE EXCEPTION 'approved budget version is immutable';
    END IF;
    IF NEW.status = 'APPROVED'
       AND (NEW.submitted_by IS NULL OR NEW.approved_by IS NULL OR NEW.submitted_by = NEW.approved_by) THEN
        RAISE EXCEPTION 'approval requires a distinct submitter and approver';
    END IF;
    IF NOT (
        OLD.status = NEW.status
        OR (OLD.status = 'DRAFT' AND NEW.status = 'SUBMITTED')
        OR (OLD.status = 'SUBMITTED' AND NEW.status IN ('APPROVED','REJECTED'))
    ) THEN
        RAISE EXCEPTION 'invalid budget version transition: % -> %', OLD.status, NEW.status;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_budget_version_transition
BEFORE UPDATE ON budget_versions
FOR EACH ROW EXECUTE FUNCTION budget_version_transition_guard();

CREATE OR REPLACE FUNCTION budget_line_edit_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    target_version UUID;
    target_status VARCHAR(24);
    target_currency VARCHAR(3);
BEGIN
    target_version := COALESCE(NEW.version_id, OLD.version_id);
    SELECT v.status, p.base_currency
      INTO target_status, target_currency
      FROM budget_versions v
      JOIN budget_plans p ON p.id = v.plan_id
     WHERE v.id = target_version;
    IF target_status IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION 'budget lines are editable only in DRAFT state';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    IF NEW.currency IS DISTINCT FROM target_currency THEN
        RAISE EXCEPTION 'budget line currency must equal the plan base currency';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_budget_line_edit
BEFORE INSERT OR UPDATE OR DELETE ON budget_lines
FOR EACH ROW EXECUTE FUNCTION budget_line_edit_guard();

CREATE OR REPLACE FUNCTION budget_audit_append_only_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'budget audit events are append-only';
END;
$$;

CREATE TRIGGER trg_budget_audit_append_only
BEFORE UPDATE OR DELETE ON budget_audit_events
FOR EACH ROW EXECUTE FUNCTION budget_audit_append_only_guard();

CREATE OR REPLACE FUNCTION budget_allocation_limit_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    source_amount DECIMAL(19,4);
    allocated_total DECIMAL(19,4);
BEGIN
    SELECT ABS(normalized_amount)
      INTO source_amount
      FROM actual_snapshots
     WHERE id = NEW.actual_snapshot_id
     FOR UPDATE;
    SELECT COALESCE(SUM(ABS(allocated_amount)), 0)
      INTO allocated_total
      FROM actual_allocations
     WHERE actual_snapshot_id = NEW.actual_snapshot_id
       AND id <> NEW.id;
    IF allocated_total + ABS(NEW.allocated_amount) > source_amount THEN
        RAISE EXCEPTION 'actual allocation exceeds the accounting row amount';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_budget_allocation_limit
BEFORE INSERT OR UPDATE ON actual_allocations
FOR EACH ROW EXECUTE FUNCTION budget_allocation_limit_guard();

CREATE UNIQUE INDEX uq_budget_line_grain_null_safe
    ON budget_lines (
        version_id, period_start, account_code, cost_center_code,
        project_code, department_code, branch_code, direction
    ) NULLS NOT DISTINCT;

ALTER TABLE budget_plans ENABLE ROW LEVEL SECURITY;
ALTER TABLE budget_plans FORCE ROW LEVEL SECURITY;
ALTER TABLE budget_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE budget_versions FORCE ROW LEVEL SECURITY;
ALTER TABLE budget_lines ENABLE ROW LEVEL SECURITY;
ALTER TABLE budget_lines FORCE ROW LEVEL SECURITY;
ALTER TABLE actual_snapshots ENABLE ROW LEVEL SECURITY;
ALTER TABLE actual_snapshots FORCE ROW LEVEL SECURITY;
ALTER TABLE actual_allocations ENABLE ROW LEVEL SECURITY;
ALTER TABLE actual_allocations FORCE ROW LEVEL SECURITY;
ALTER TABLE budget_commitments ENABLE ROW LEVEL SECURITY;
ALTER TABLE budget_commitments FORCE ROW LEVEL SECURITY;
ALTER TABLE budget_forecasts ENABLE ROW LEVEL SECURITY;
ALTER TABLE budget_forecasts FORCE ROW LEVEL SECURITY;
ALTER TABLE budget_sync_checkpoints ENABLE ROW LEVEL SECURITY;
ALTER TABLE budget_sync_checkpoints FORCE ROW LEVEL SECURITY;
ALTER TABLE budget_reconciliation_runs ENABLE ROW LEVEL SECURITY;
ALTER TABLE budget_reconciliation_runs FORCE ROW LEVEL SECURITY;
ALTER TABLE budget_audit_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE budget_audit_events FORCE ROW LEVEL SECURITY;

CREATE POLICY budget_plans_tenant_policy ON budget_plans
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''));
CREATE POLICY budget_versions_tenant_policy ON budget_versions
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''));
CREATE POLICY budget_lines_tenant_policy ON budget_lines
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''));
CREATE POLICY actual_snapshots_tenant_policy ON actual_snapshots
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''));
CREATE POLICY actual_allocations_tenant_policy ON actual_allocations
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''));
CREATE POLICY budget_commitments_tenant_policy ON budget_commitments
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''));
CREATE POLICY budget_forecasts_tenant_policy ON budget_forecasts
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''));
CREATE POLICY budget_sync_checkpoints_tenant_policy ON budget_sync_checkpoints
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''));
CREATE POLICY budget_reconciliation_tenant_policy ON budget_reconciliation_runs
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''));
CREATE POLICY budget_audit_tenant_policy ON budget_audit_events
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''))
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), ''));

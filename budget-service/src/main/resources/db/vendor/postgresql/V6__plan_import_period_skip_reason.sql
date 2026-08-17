-- gitops#3474: ERP PLAN_DATE can fall outside the budget's fiscal year (a
-- 2025-06 row inside the 2026 TEST demo budget, measured live 2026-08-17).
-- The import now skips such rows explicitly; the skip-reason CHECK must admit
-- the new reason.

ALTER TABLE budget_plan_import_skips
    DROP CONSTRAINT ck_plan_import_skip_reason;

ALTER TABLE budget_plan_import_skips
    ADD CONSTRAINT ck_plan_import_skip_reason
        CHECK (reason IN (
            'SCENARIO_PLAN',
            'MISSING_ACCOUNT_CODE',
            'MISSING_PERIOD',
            'PERIOD_OUTSIDE_FISCAL_YEAR',
            'ZERO_AMOUNT',
            'NEGATIVE_AMOUNT'));

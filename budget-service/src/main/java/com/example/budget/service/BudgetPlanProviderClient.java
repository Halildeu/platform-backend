package com.example.budget.service;

import static com.example.budget.api.WorkcubePlanImportDtos.ProviderBudgetPlanPage;

/** Read-only Workcube budget-plan provider (report-service) client (#3454). */
public interface BudgetPlanProviderClient {

    ProviderBudgetPlanPage fetchPlans(
            String authorization,
            long companyId,
            int fiscalYear,
            String cursor,
            int limit);
}

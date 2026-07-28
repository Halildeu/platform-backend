package com.example.budget.security;

import java.util.Set;

public record BudgetActor(
        String tenantId,
        long companyId,
        String subject,
        Set<Long> allowedProjectIds,
        boolean superAdmin) {

    public BudgetActor {
        allowedProjectIds = allowedProjectIds == null ? Set.of() : Set.copyOf(allowedProjectIds);
    }

    public boolean canAccessProject(long projectId) {
        return superAdmin || allowedProjectIds.contains(projectId);
    }
}

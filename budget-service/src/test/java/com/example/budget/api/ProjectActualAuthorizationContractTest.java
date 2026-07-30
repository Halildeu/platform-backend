package com.example.budget.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class ProjectActualAuthorizationContractTest {

    @Test
    void projectActualEndpointsRequireScopeAndDedicatedBudgetRole() {
        assertGuard("createBinding", "SCOPE_budget:write", "ROLE_BUDGET_PLANNER");
        assertGuard("findBinding", "SCOPE_budget:read", "ROLE_BUDGET_PLANNER");
        assertGuard("sync", "SCOPE_budget:write", "ROLE_BUDGET_PLANNER");
        assertGuard("rows", "SCOPE_budget:read", "ROLE_BUDGET_PLANNER");
        assertGuard("sourceLines", "SCOPE_budget:read", "ROLE_BUDGET_PLANNER");
        assertGuard("sourceDocument", "SCOPE_budget:read", "ROLE_BUDGET_PLANNER");
        assertGuard("summary", "SCOPE_budget:read", "ROLE_BUDGET_PLANNER");
        assertGuard("replaceCostRules", "SCOPE_budget:approve", "ROLE_BUDGET_APPROVER");
    }

    private void assertGuard(String methodName, String scope, String role) {
        Method method = Arrays.stream(ProjectActualController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        String expression = method.getAnnotation(PreAuthorize.class).value();
        assertThat(expression).contains(scope, role, " and ");
    }
}

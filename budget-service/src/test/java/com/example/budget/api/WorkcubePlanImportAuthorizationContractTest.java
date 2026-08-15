package com.example.budget.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class WorkcubePlanImportAuthorizationContractTest {

    @Test
    void importEndpointRequiresWriteScopeAndDedicatedPlannerRole() {
        Method method = Arrays.stream(WorkcubePlanImportController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("importPlans"))
                .findFirst()
                .orElseThrow();
        String expression = method.getAnnotation(PreAuthorize.class).value();
        assertThat(expression)
                .contains("SCOPE_budget:write", "ROLE_BUDGET_PLANNER", " and ");
    }
}

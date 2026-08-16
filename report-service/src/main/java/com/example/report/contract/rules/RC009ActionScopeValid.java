package com.example.report.contract.rules;

import com.example.report.contract.report.ContractViolation;
import com.example.report.registry.ActionDefinition;
import com.example.report.registry.ReportDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * RC-009 — actions[].scope must be {@code grid} | {@code row} |
 * {@code selection}.
 *
 * <p>Phase 2 Program 3 (Action Menu Standard) frontend three-slot rendering;
 * the scope value is a backend contract enforced at build time. Implemented
 * for issue #799 (1c migration) — the actions field is now parsed into
 * {@link ReportDefinition}; the deferred-stub registry entry is gone.
 */
public final class RC009ActionScopeValid implements ContractRule {

    private static final Set<String> VALID_SCOPES = Set.of("grid", "row", "selection");

    @Override
    public String ruleId() {
        return "RC-009";
    }

    @Override
    public List<ContractViolation> validate(ReportDefinition def) {
        if (def.actions() == null) {
            return List.of();
        }
        List<ContractViolation> violations = new ArrayList<>();
        for (ActionDefinition action : def.actions()) {
            String scope = action.scope();
            if (scope == null || scope.isBlank()) {
                violations.add(ContractViolation.fail(
                        ruleId(), def.key(), "actions[" + action.id() + "].scope",
                        "action scope is null or blank; must be one of " + VALID_SCOPES));
            } else if (!VALID_SCOPES.contains(scope)) {
                violations.add(ContractViolation.fail(
                        ruleId(), def.key(), "actions[" + action.id() + "].scope",
                        "ENUM_VIOLATION: scope='" + scope + "' not in " + VALID_SCOPES
                                + " (Phase 2 Program 3 three-slot rendering contract)"));
            }
        }
        return violations;
    }
}

package com.example.report.registry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A single entry of a report's action menu (Phase 2 Program 3 — Action Menu
 * Standard; issue #799 "1c migration").
 *
 * <p>Shape only: semantic constraints are deliberately NOT enforced here so
 * the contract gate can report them as {@code ContractViolation}s instead of
 * a binding exception —
 * <ul>
 *   <li>RC-009: {@code scope} must be {@code grid} | {@code row} |
 *       {@code selection} (frontend three-slot rendering contract).</li>
 *   <li>RC-010: {@code destructive == true} requires a non-blank
 *       {@code permission} (no REPORT_VIEW inherit) and a non-blank
 *       {@code confirm} payload (modal text).</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ActionDefinition(
        String id,
        String label,
        String scope,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean destructive,
        @JsonInclude(JsonInclude.Include.NON_NULL) String permission,
        @JsonInclude(JsonInclude.Include.NON_NULL) String confirm) {

    public boolean isDestructive() {
        return Boolean.TRUE.equals(destructive);
    }
}

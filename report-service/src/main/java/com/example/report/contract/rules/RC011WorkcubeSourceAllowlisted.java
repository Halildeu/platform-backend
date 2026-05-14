package com.example.report.contract.rules;

import com.example.report.contract.report.ContractViolation;
import com.example.report.contract.schema.ReportingAllowlist;
import com.example.report.registry.ReportDefinition;
import java.util.List;

/**
 * RC-011 — Workcube source table must be in {@link ReportingAllowlist#V1}.
 *
 * <p>Phase 2 Program 11.1 (Codex {@code 019e258f} iter-17 PARTIAL absorb):
 * every {@code "source": "..."} entry in a report definition must address
 * a table the platform officially exposes via the Workcube reporting
 * allowlist. Unknown source tables fail the build, preventing accidental
 * inclusion of tables that haven't been catalogued / had type mapping
 * resolved.
 *
 * <p><b>Scope (PR-1)</b>: only the {@code source} field is checked.
 * {@code sourceQuery}-style reports (yearly partitioned, custom SQL) are
 * scanned in Adım 11.2 (WorkcubeQueryAdapter) where SQL parsing surfaces
 * the actual tables referenced.
 *
 * <p><b>Why a separate rule</b> (vs piggy-backing on {@code
 * SchemaTruthLookupPolicy.BUILD_DETERMINISTIC}): the policy enum encodes
 * tier-fallback strategy (Tier 1 vs 2 vs 3), not allowlist membership
 * — Codex iter-17 S3 explicit: "Policy enum'u semantik tier davranışı
 * için; allowlist üyeliği ayrı validator rule olmalı."
 */
public final class RC011WorkcubeSourceAllowlisted implements ContractRule {

    @Override
    public String ruleId() {
        return "RC-011";
    }

    @Override
    public List<ContractViolation> validate(ReportDefinition def) {
        if (def.source() == null || def.source().isBlank()) {
            return List.of();
        }

        if (!ReportingAllowlist.containsV1(def.source())) {
            return List.of(ContractViolation.fail(
                    ruleId(), def.key(), "source",
                    "Workcube source table '" + def.source()
                            + "' is not in ReportingAllowlist.V1 (pre-SEAL snapshot, "
                            + "30 tables). Add the table to ReportingAllowlist or "
                            + "validate via Faz 16.1 annex 2A SEAL before referencing."));
        }
        return List.of();
    }
}

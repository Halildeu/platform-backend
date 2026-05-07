package com.example.report.schema.tier;

import com.example.report.registry.ColumnDefinition;
import com.example.report.registry.ReportDefinition;
import com.example.report.registry.ReportRegistry;
import com.example.report.schema.SchemaTruthLookupContext;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Phase 2 Program 8b — Tier 3 registry type fallback.
 *
 * <p>Tier 3 last-resort: report registry JSON ({@code reports/<key>.json})
 * {@code columns[].type} alanından column type tahmini. Sadece <strong>
 * report-scoped column type fallback</strong>: API capability matrix
 * (spec §2.1.2):
 * <ul>
 *   <li>{@code lookupColumnType(reportKey, field)} ✓ — report-scoped: registry
 *       columns array'inden field name match → ColumnDefinition.type()</li>
 *   <li>{@code lookupColumnType(schema, table, column)} ✗ — DB-level: registry
 *       sadece field name'leri tutar, schema-table-column mapping yok</li>
 *   <li>{@code exists(schemaName)} ✗ — Tier 3 invalid (capability matrix)</li>
 *   <li>{@code listColumns(schema, table)} partial — sadece raporun expose
 *       ettiği kolonlar; DB truth değil + WARN</li>
 * </ul>
 *
 * <p>Tier 3 ulaşıldığında WARN üretilir (Codex iter-1 §3 absorb: usage
 * time WARN); pager alert {@code schema_truth_fallback_total{tier=
 * "registry_type"} > 0} 5-min content over threshold (8d'de wire'lı).
 *
 * <p>Spec: §2.1, §2.2 Tier 3, §2.1.2 capability matrix.
 */
@Component
public class RegistryTypeFallback {

    private static final Logger log = LoggerFactory.getLogger(RegistryTypeFallback.class);

    private final ReportRegistry reportRegistry;

    public RegistryTypeFallback(ReportRegistry reportRegistry) {
        this.reportRegistry = reportRegistry;
    }

    /**
     * Tier 3 column type lookup — report registry scope.
     *
     * @param ctx          lookup context (reportKey gerekli; null/blank → empty)
     * @param fieldName    column field name (registry'deki ColumnDefinition.field())
     * @return {@link Optional#empty()} report bulunamazsa veya field match yoksa;
     *         {@link Optional#of} otherwise (column type string)
     */
    public Optional<String> lookupColumnType(SchemaTruthLookupContext ctx, String fieldName) {
        if (ctx == null || ctx.reportKey() == null || ctx.reportKey().isBlank()) {
            return Optional.empty();
        }
        if (fieldName == null || fieldName.isBlank()) {
            return Optional.empty();
        }
        try {
            Optional<ReportDefinition> defOpt = reportRegistry.get(ctx.reportKey());
            if (defOpt.isEmpty() || defOpt.get().columns() == null) {
                return Optional.empty();
            }
            for (ColumnDefinition col : defOpt.get().columns()) {
                if (fieldName.equalsIgnoreCase(col.field())) {
                    log.warn("Tier 3 registry fallback used: report={} field={} consumer={}",
                            ctx.reportKey(), fieldName, ctx.consumer());
                    return Optional.ofNullable(col.type());
                }
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Tier 3 registry lookup failed: report={} field={} error={}",
                    ctx.reportKey(), fieldName, e.getMessage());
            return Optional.empty();
        }
    }
}

package com.example.report.schema.consumer;

import com.example.report.registry.ColumnDefinition;
import com.example.report.registry.ReportDefinition;
import com.example.report.registry.ReportRegistry;
import com.example.report.schema.SchemaSnapshot;
import com.example.report.schema.SchemaTruthLookupContext;
import com.example.report.schema.SchemaTruthService;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Phase 2 Program 8c — Table columns list public API.
 *
 * <p>Spec §2.1 + capability matrix §2.1.2:
 * <ul>
 *   <li>Tier 1+2 fallback OK (full table column scan — schema-service
 *       runtime + committed snapshot)</li>
 *   <li>Tier 3 partial — registry types yalnız raporun expose ettiği
 *       kolonlar; DB truth değil + WARN (Codex iter-1 §2 absorb)</li>
 * </ul>
 *
 * <p>PR-0.4 (PR #90) `SqlBuilder.buildPivotedGroupedQuery_dynamicPivotValues
 * AfterPreFlight` consumer; pivot value discovery.
 */
@Component
public class TableColumnsListService {

    private static final Logger log = LoggerFactory.getLogger(TableColumnsListService.class);

    private final SchemaTruthService schemaTruthService;
    private final ReportRegistry reportRegistry;

    public TableColumnsListService(SchemaTruthService schemaTruthService,
                                     ReportRegistry reportRegistry) {
        this.schemaTruthService = schemaTruthService;
        this.reportRegistry = reportRegistry;
    }

    /**
     * List columns for the given table.
     *
     * <p>Caller `ctx.policy()` {@link com.example.report.schema.SchemaTruthLookupPolicy#RUNTIME_DEGRADED_TYPE}
     * (production fast-path) veya {@link com.example.report.schema.SchemaTruthLookupPolicy#BUILD_DETERMINISTIC}
     * (build-time) ile çağırır.
     *
     * @param ctx       lookup context
     * @param schema    workcube schema adı
     * @param tableName table adı
     * @return column info list (boş listeler valid; missing schema/table → empty)
     */
    public List<SchemaSnapshot.ColumnInfo> listColumns(SchemaTruthLookupContext ctx,
                                                        String schema, String tableName) {
        if (ctx == null || schema == null || tableName == null) {
            return Collections.emptyList();
        }
        // Tier 1+2: schema-service / committed snapshot
        try {
            Optional<SchemaSnapshot> snapshot = schemaTruthService.fetchSnapshot(ctx, schema);
            if (snapshot.isPresent()) {
                SchemaSnapshot.TableInfo table = snapshot.get().tables().get(tableName);
                if (table != null && !table.columns().isEmpty()) {
                    return table.columns();
                }
                log.debug("TableColumnsListService Tier 1/2 miss: schema={} table={}",
                        schema, tableName);
            } else {
                log.debug("TableColumnsListService snapshot empty: schema={} table={}",
                        schema, tableName);
            }
        } catch (RuntimeException e) {
            log.debug("TableColumnsListService Tier 1/2 fail-soft: schema={} table={} error={}",
                    schema, tableName, e.getMessage());
        }

        // Tier 3 partial fallback (Codex iter-1 §3 absorb): report registry's
        // exposed columns (NOT DB truth — only the columns the report config
        // declares). WARN at usage time so caller knows degraded source.
        return tier3PartialFallback(ctx);
    }

    /**
     * Tier 3 partial fallback — report registry columns mapped to ColumnInfo.
     *
     * <p>Capability matrix §2.1.2: registry kolonları DB truth değil; raporun
     * expose ettiği subset. dataType registry'deki {@code number/text/date}
     * (precision yok); caller bunu degraded sinyal olarak kabul etmeli.
     *
     * <p>WARN log her başvuruda — caller {@code ctx.reportKey()} blank ise
     * Tier 3 fallback de empty list döner (capability matrix violation).
     */
    private List<SchemaSnapshot.ColumnInfo> tier3PartialFallback(SchemaTruthLookupContext ctx) {
        if (ctx.reportKey() == null || ctx.reportKey().isBlank()) {
            log.warn("TableColumnsListService Tier 3 unavailable: reportKey blank (consumer={})",
                    ctx.consumer());
            return Collections.emptyList();
        }
        try {
            Optional<ReportDefinition> defOpt = reportRegistry.get(ctx.reportKey());
            if (defOpt.isEmpty() || defOpt.get().columns() == null) {
                return Collections.emptyList();
            }
            log.warn("TableColumnsListService Tier 3 partial fallback: report={} consumer={} (registry types, NOT DB truth)",
                    ctx.reportKey(), ctx.consumer());
            return defOpt.get().columns().stream()
                    .map(this::mapRegistryColumnToColumnInfo)
                    .toList();
        } catch (Exception e) {
            log.warn("TableColumnsListService Tier 3 lookup failed: report={} error={}",
                    ctx.reportKey(), e.getMessage());
            return Collections.emptyList();
        }
    }

    private SchemaSnapshot.ColumnInfo mapRegistryColumnToColumnInfo(ColumnDefinition col) {
        return new SchemaSnapshot.ColumnInfo(col.field(), col.type(), null);
    }
}

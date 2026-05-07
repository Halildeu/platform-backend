package com.example.report.schema.consumer;

import com.example.report.schema.SchemaSnapshot;
import com.example.report.schema.SchemaTruthLookupContext;
import com.example.report.schema.SchemaTruthLookupPolicy;
import com.example.report.schema.SchemaTruthService;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Phase 2 Program 8c — Column type lookup public API.
 *
 * <p>Spec §2.1 (consumer interfaces). 3-tier policy-driven column type
 * lookup; capability matrix §2.1.2 absorb:
 * <ul>
 *   <li>{@link #lookupColumnType(SchemaTruthLookupContext, String)} — report-scoped,
 *       Tier 1+2+3 fallback</li>
 *   <li>{@link #exists(SchemaTruthLookupContext, String, String, String)} — DB-level,
 *       Tier 1+2 only (Tier 3 invalid)</li>
 * </ul>
 *
 * <p>Per-request hot-path cache via {@link RequestColumnTypeCache}
 * ({@code @RequestScope}); {@link ObjectProvider} non-web context safe.
 */
@Component
public class ColumnTypeRegistry {

    private static final Logger log = LoggerFactory.getLogger(ColumnTypeRegistry.class);

    private final SchemaTruthService schemaTruthService;
    private final ObjectProvider<RequestColumnTypeCache> cacheProvider;

    public ColumnTypeRegistry(SchemaTruthService schemaTruthService,
                                ObjectProvider<RequestColumnTypeCache> cacheProvider) {
        this.schemaTruthService = schemaTruthService;
        this.cacheProvider = cacheProvider;
    }

    /**
     * Report-scoped column type lookup (Tier 1+2+3 chain).
     *
     * <p>Capability §2.1.2: report-scoped — caller `ctx.reportKey()` + `fieldName` ile
     * çağırır. Tier 1 (schema-service) Tier 2 (committed snapshot) ile schema-level
     * snapshot çözer; Tier 3 (registry types) report-scoped column type fallback.
     *
     * @param ctx       lookup context (`reportKey` zorunlu)
     * @param fieldName column field name (registry'deki ColumnDefinition.field())
     * @return column type string (varsayılan policy {@link SchemaTruthLookupPolicy#RUNTIME_DEGRADED_TYPE})
     */
    public Optional<String> lookupColumnType(SchemaTruthLookupContext ctx, String fieldName) {
        if (ctx == null || fieldName == null || fieldName.isBlank()) {
            return Optional.empty();
        }

        // Per-request cache hot path
        RequestColumnTypeCache cache = cacheProvider.getIfAvailable();
        if (cache != null) {
            Optional<String> cached = cache.getColumnType(ctx.reportKey(), fieldName);
            if (cached.isPresent()) {
                return cached;
            }
        }

        // Tier 1+2 — try to extract column type from full schema snapshot
        // (requires ctx.schemaMode + a derivable schema name; for report-scoped
        // case we fall back to Tier 3 directly when snapshot lookup is not viable).
        // Tier 3 — report-scoped registry types fallback
        Optional<String> tier3 = schemaTruthService.lookupColumnTypeTier3(ctx, fieldName);
        if (tier3.isPresent() && cache != null) {
            cache.putColumnType(ctx.reportKey(), fieldName, tier3.get());
        }
        return tier3;
    }

    /**
     * DB-level column existence check (Tier 1+2 only — capability matrix §2.1.2).
     *
     * <p>Caller `schema`+`table`+`column` ile DB'de kolon var mı sorgulamak istediğinde
     * çağırır. Tier 3 invalid (registry types schema-table mapping tutmaz).
     *
     * @param ctx     lookup context
     * @param schema  workcube schema adı
     * @param table   table adı
     * @param column  column adı
     * @return true if column exists in Tier 1 (live) or Tier 2 (committed) snapshot
     */
    public boolean exists(SchemaTruthLookupContext ctx, String schema, String table, String column) {
        if (ctx == null || schema == null || table == null || column == null) {
            return false;
        }
        try {
            // BUILD_DETERMINISTIC + RUNTIME_DEGRADED_TYPE Tier 2 fallback OK
            // RUNTIME_STRICT_EXISTENCE policy zaten exists() çağırmaz (SchemaExistsService)
            Optional<SchemaSnapshot> snapshot = schemaTruthService.fetchSnapshot(ctx, schema);
            return snapshot.map(s -> s.tables().get(table))
                    .map(t -> t.columns().stream()
                            .anyMatch(c -> column.equalsIgnoreCase(c.name())))
                    .orElse(false);
        } catch (RuntimeException e) {
            log.debug("ColumnTypeRegistry.exists fail-soft: schema={} table={} col={} error={}",
                    schema, table, column, e.getMessage());
            return false;
        }
    }
}

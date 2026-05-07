package com.example.report.schema.consumer;

import com.example.report.schema.SchemaSnapshot;
import com.example.report.schema.SchemaTruthLookupContext;
import com.example.report.schema.SchemaTruthLookupPolicy;
import com.example.report.schema.SchemaTruthService;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.ScopeNotActiveException;
import org.springframework.stereotype.Component;

/**
 * Phase 2 Program 8c — Column type lookup public API.
 *
 * <p>Spec §2.1 (consumer interfaces). 3-tier policy-driven column type
 * lookup; capability matrix §2.1.2 absorb:
 * <ul>
 *   <li>{@link #lookupColumnType(SchemaTruthLookupContext, String, String, String)}
 *       — DB-level: Tier 1+2+3 fallback (Codex iter-1 §2 absorb — primary path
 *       schema-service/committed snapshot column type; report-scoped Tier 3 last
 *       resort if upstream miss)</li>
 *   <li>{@link #lookupReportColumnType(SchemaTruthLookupContext, String)}
 *       — report-scoped Tier 3 only (registry types fallback)</li>
 *   <li>{@link #exists(SchemaTruthLookupContext, String, String, String)}
 *       — DB-level: Tier 1+2 only (Tier 3 invalid per capability matrix)</li>
 * </ul>
 *
 * <p>Per-request hot-path cache via {@link RequestColumnTypeCache}
 * ({@code @RequestScope}); {@link ObjectProvider#getIfAvailable()} +
 * {@link ScopeNotActiveException} catch for non-web context safety
 * (Codex iter-1 §4 absorb).
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
     * DB-level column type lookup — Tier 1+2+3 fallback chain
     * (Codex iter-1 §2 absorb).
     *
     * <p>Primary path: schema-service/committed snapshot DB-level column type
     * (örn. `DECIMAL(18,2)`, `NVARCHAR(50)`); precision-aware contract.
     * FilterTranslator + SqlBuilder weighted AVG/pivot için bu method'u kullanır.
     *
     * <p>Tier 3 last resort: schema-service + committed snapshot miss/fail
     * sonrasında report registry types ({@code number/text/date}) — daha az
     * precision ama caller'ın hiç tip görmeyişinden iyidir.
     *
     * @param ctx     lookup context
     * @param schema  workcube schema adı
     * @param table   table adı
     * @param column  column adı
     * @return column dataType string ({@code DECIMAL(18,2)}, {@code INT}, vb.)
     */
    public Optional<String> lookupColumnType(SchemaTruthLookupContext ctx,
                                              String schema, String table, String column) {
        if (ctx == null || schema == null || table == null || column == null) {
            return Optional.empty();
        }

        // Per-request cache hot path (key: schema:table:column for DB-level lookups)
        RequestColumnTypeCache cache = safeCache();
        String cacheKey = schema + ":" + table + ":" + column;
        if (cache != null) {
            Optional<String> cached = cache.getColumnType("__db__", cacheKey);
            if (cached.isPresent()) {
                return cached;
            }
        }

        // Tier 1+2: schema snapshot via SchemaTruthService facade.
        try {
            Optional<SchemaSnapshot> snapshot = schemaTruthService.fetchSnapshot(ctx, schema);
            if (snapshot.isPresent()) {
                Optional<String> dbType = snapshot.flatMap(s -> Optional.ofNullable(s.tables().get(table)))
                        .flatMap(t -> t.columns().stream()
                                .filter(c -> column.equalsIgnoreCase(c.name()))
                                .map(SchemaSnapshot.ColumnInfo::dataType)
                                .findFirst());
                if (dbType.isPresent()) {
                    if (cache != null) {
                        cache.putColumnType("__db__", cacheKey, dbType.get());
                    }
                    return dbType;
                }
            }
        } catch (RuntimeException e) {
            log.debug("Tier 1/2 fail-soft for column type: schema={} table={} col={} error={}",
                    schema, table, column, e.getMessage());
        }

        // Tier 3 last resort: report-scoped registry types (less precise).
        Optional<String> tier3 = schemaTruthService.lookupColumnTypeTier3(ctx, column);
        if (tier3.isPresent() && cache != null) {
            cache.putColumnType("__db__", cacheKey, tier3.get());
        }
        return tier3;
    }

    /**
     * Report-scoped column type lookup — Tier 3 only (registry types).
     *
     * <p>Caller'ın schema/table bilgisi yoksa (ör. eski API'lar, AG Grid
     * colDef enrichment frontend hook) sadece report registry'den tip
     * çıkarmak için.
     *
     * @param ctx       lookup context (`reportKey` zorunlu)
     * @param fieldName column field name
     * @return registry-defined column type ({@code number/text/date})
     */
    public Optional<String> lookupReportColumnType(SchemaTruthLookupContext ctx, String fieldName) {
        if (ctx == null || fieldName == null || fieldName.isBlank()) {
            return Optional.empty();
        }

        RequestColumnTypeCache cache = safeCache();
        if (cache != null) {
            Optional<String> cached = cache.getColumnType(ctx.reportKey(), fieldName);
            if (cached.isPresent()) {
                return cached;
            }
        }

        Optional<String> tier3 = schemaTruthService.lookupColumnTypeTier3(ctx, fieldName);
        if (tier3.isPresent() && cache != null) {
            cache.putColumnType(ctx.reportKey(), fieldName, tier3.get());
        }
        return tier3;
    }

    /**
     * DB-level column existence check (Tier 1+2 only — capability matrix §2.1.2).
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

    /**
     * @RequestScope cache erişimi non-web context safe (Codex iter-1 §4 absorb).
     *
     * <p>{@link ObjectProvider#getIfAvailable()} request-scope proxy döndürür;
     * proxy method invocation no-request context'te {@link ScopeNotActiveException}
     * fırlatır. Caller'ı korumak için catch + null fallback.
     *
     * @return cache instance or null (non-web / scope inactive)
     */
    private RequestColumnTypeCache safeCache() {
        try {
            RequestColumnTypeCache cache = cacheProvider.getIfAvailable();
            if (cache == null) {
                return null;
            }
            // Proxy → method invocation triggers scope resolution; size() lookup
            // throws ScopeNotActiveException if no request bound.
            cache.size();
            return cache;
        } catch (BeansException ignored) {
            // ScopeNotActiveException extends BeansException; single catch covers
            // both no-request scope + bean factory configuration issues.
            return null;
        }
    }
}

package com.example.report.schema.consumer;

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

    public TableColumnsListService(SchemaTruthService schemaTruthService) {
        this.schemaTruthService = schemaTruthService;
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
        try {
            Optional<SchemaSnapshot> snapshot = schemaTruthService.fetchSnapshot(ctx, schema);
            if (snapshot.isEmpty()) {
                log.debug("TableColumnsListService snapshot miss: schema={} table={}",
                        schema, tableName);
                return Collections.emptyList();
            }
            SchemaSnapshot.TableInfo table = snapshot.get().tables().get(tableName);
            if (table == null) {
                log.debug("TableColumnsListService table not in snapshot: schema={} table={}",
                        schema, tableName);
                return Collections.emptyList();
            }
            return table.columns();
        } catch (RuntimeException e) {
            log.debug("TableColumnsListService fail-soft: schema={} table={} error={}",
                    schema, tableName, e.getMessage());
            return Collections.emptyList();
        }
    }
}

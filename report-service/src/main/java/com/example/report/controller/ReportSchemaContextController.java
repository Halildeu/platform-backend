package com.example.report.controller;

import com.example.report.registry.ReportDefinition;
import com.example.report.registry.ReportRegistry;
import com.example.report.schema.SchemaSnapshot;
import com.example.report.schema.SchemaTruthLookupContext;
import com.example.report.schema.SchemaTruthLookupPolicy;
import com.example.report.schema.SchemaTruthService;
import com.example.report.schema.tier.CommittedSnapshotLoader;
import com.example.report.schema.tier.SchemaServiceClient;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 2 Program 8e — Schema context endpoint for frontend useReportSchemaContext hook.
 *
 * <p>Spec §2.5: {@code GET /api/v1/reports/{key}/schema-context} → AG Grid
 * colDef enrichment için column types + {@code X-Schema-Truth-Tier} response
 * header (canonical tier signal).
 *
 * <p>Bu controller {@link SchemaTruthService} facade üzerinden 3-tier dispatch
 * ile kolon tiplerini çözer; hangi tier serve ettiğini header'da yansıtır
 * (frontend transparent debug + Tier 3 console.warn yolu).
 */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportSchemaContextController {

    private static final Logger log = LoggerFactory.getLogger(ReportSchemaContextController.class);
    public static final String TIER_HEADER = "X-Schema-Truth-Tier";

    private final ReportRegistry reportRegistry;
    private final SchemaServiceClient schemaServiceClient;
    private final CommittedSnapshotLoader committedSnapshotLoader;
    private final SchemaTruthService schemaTruthService;

    public ReportSchemaContextController(ReportRegistry reportRegistry,
                                           SchemaServiceClient schemaServiceClient,
                                           CommittedSnapshotLoader committedSnapshotLoader,
                                           SchemaTruthService schemaTruthService) {
        this.reportRegistry = reportRegistry;
        this.schemaServiceClient = schemaServiceClient;
        this.committedSnapshotLoader = committedSnapshotLoader;
        this.schemaTruthService = schemaTruthService;
    }

    @GetMapping("/{reportKey}/schema-context")
    public ResponseEntity<SchemaContextResponse> getSchemaContext(@PathVariable String reportKey) {
        Optional<ReportDefinition> defOpt = reportRegistry.get(reportKey);
        if (defOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ReportDefinition def = defOpt.get();

        SchemaTruthLookupContext ctx = new SchemaTruthLookupContext(
                reportKey, def.schemaMode(),
                SchemaTruthLookupPolicy.RUNTIME_DEGRADED_TYPE,
                "schema_context_endpoint");

        // Tier dispatch with explicit tier tracking (canonical X-Schema-Truth-Tier)
        TierResolution tierResolution = resolveTier(ctx, def.sourceSchema());

        Map<String, String> columnTypes = extractColumnTypes(tierResolution.snapshot, def);

        SchemaContextResponse body = new SchemaContextResponse(
                reportKey, tierResolution.tier, columnTypes);

        return ResponseEntity.ok()
                .header(TIER_HEADER, tierResolution.tier)
                .body(body);
    }

    private TierResolution resolveTier(SchemaTruthLookupContext ctx, String schemaName) {
        if (schemaName == null || schemaName.isBlank()) {
            return new TierResolution(Optional.empty(), com.example.report.schema.SchemaTruthResult.TIER_MISS);
        }

        // Tier 1: schema-service
        try {
            Optional<SchemaSnapshot> tier1 = schemaServiceClient.fetchSnapshot(ctx, schemaName);
            if (tier1.isPresent()) {
                return new TierResolution(tier1, com.example.report.schema.SchemaTruthResult.TIER_SCHEMA_SERVICE);
            }
        } catch (RuntimeException e) {
            log.debug("schema-context Tier 1 fail-soft: schema={} error={}", schemaName, e.getMessage());
        }

        // Tier 2: committed snapshot
        Optional<SchemaSnapshot> tier2 = committedSnapshotLoader.lookup(ctx, schemaName);
        if (tier2.isPresent()) {
            return new TierResolution(tier2, com.example.report.schema.SchemaTruthResult.TIER_COMMITTED_SNAPSHOT);
        }

        // Tier 3: registry types — fallback (column types will be filled from registry directly).
        // Snapshot empty + tier="registry_type" frontend warns transparency.
        return new TierResolution(Optional.empty(), com.example.report.schema.SchemaTruthResult.TIER_REGISTRY_TYPE);
    }

    private Map<String, String> extractColumnTypes(Optional<SchemaSnapshot> snapshotOpt,
                                                     ReportDefinition def) {
        Map<String, String> types = new HashMap<>();
        if (snapshotOpt.isPresent() && def.source() != null) {
            SchemaSnapshot.TableInfo table = snapshotOpt.get().tables().get(def.source());
            if (table != null) {
                for (SchemaSnapshot.ColumnInfo col : table.columns()) {
                    if (col.dataType() != null) {
                        types.put(col.name(), col.dataType());
                    }
                }
                if (!types.isEmpty()) {
                    return types;
                }
            }
        }
        // Tier 3: pull column types from report registry definition
        if (def.columns() != null) {
            for (var col : def.columns()) {
                if (col.type() != null) {
                    types.put(col.field(), col.type());
                }
            }
        }
        return types;
    }

    /** Internal helper — tier + snapshot pair. */
    private record TierResolution(Optional<SchemaSnapshot> snapshot, String tier) {}

    /**
     * Schema context response shape — frontend useReportSchemaContext consumer.
     *
     * @param reportKey   Report registry key (echo from path)
     * @param tier        "schema_service" | "committed_snapshot" | "registry_type"
     *                    (canonical: also in X-Schema-Truth-Tier header)
     * @param columnTypes Field name → column type ({@code DECIMAL(18,2)},
     *                    {@code number}, vb.); empty if all tiers missed
     */
    public record SchemaContextResponse(
            String reportKey,
            String tier,
            Map<String, String> columnTypes
    ) {
        public SchemaContextResponse {
            if (columnTypes == null) {
                columnTypes = Collections.emptyMap();
            }
        }
    }
}

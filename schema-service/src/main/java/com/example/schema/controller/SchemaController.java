package com.example.schema.controller;

import com.example.schema.model.Relationship;
import com.example.schema.model.ReportingContractSnapshot;
import com.example.schema.model.SchemaSnapshot;
import com.example.schema.model.TableInfo;
import com.example.schema.service.ReportingContractService;
import com.example.schema.service.SchemaExtractService;
import com.example.schema.service.SchemaSnapshotService;
import com.example.schema.service.SchemaLookupService;
import com.example.schema.service.PathFinderService;
import com.example.schema.service.SchemaHealthService;
import com.example.schema.service.SchemaDriftService;
import com.example.schema.service.QuerySuggestionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/schema")
public class SchemaController {

    private static final String INTERNAL_API_KEY_HEADER = "X-Internal-Api-Key";

    private final SchemaExtractService extractService;
    private final SchemaSnapshotService snapshotService;
    private final SchemaLookupService lookupService;
    private final PathFinderService pathFinderService;
    private final SchemaHealthService healthService;
    private final SchemaDriftService driftService;
    private final QuerySuggestionService querySuggestionService;
    private final ReportingContractService reportingContractService;

    @Value("${schema.default-schema:workcube_mikrolink}")
    private String defaultSchema;

    @Value("${schema.cache-ttl-minutes:60}")
    private int cacheTtlMinutes;

    @Value("${schema.snapshot.internal-api-key:}")
    private String snapshotInternalApiKey;

    public SchemaController(SchemaExtractService extractService,
                            SchemaSnapshotService snapshotService,
                            SchemaLookupService lookupService,
                            PathFinderService pathFinderService,
                            SchemaHealthService healthService,
                            SchemaDriftService driftService,
                            QuerySuggestionService querySuggestionService,
                            ReportingContractService reportingContractService) {
        this.extractService = extractService;
        this.snapshotService = snapshotService;
        this.lookupService = lookupService;
        this.pathFinderService = pathFinderService;
        this.healthService = healthService;
        this.driftService = driftService;
        this.querySuggestionService = querySuggestionService;
        this.reportingContractService = reportingContractService;
    }

    /**
     * Full schema snapshot — tables, relationships, domains, analysis.
     * Used by the frontend to load the entire schema graph + report-service
     * SchemaTruthService Tier 1 (Phase 2 Program 8).
     *
     * <p>Auth model (Phase 2 Program 8a, Codex iter-1 §3 + iter-2 §1 absorb):
     * <strong>internal key OR valid JWT</strong>. Two parallel paths:
     * <ul>
     *   <li>Internal service-to-service: {@code X-Internal-Api-Key} header
     *       (matches existing master-data pattern). Used by report-service
     *       SchemaTruthService Tier 1 client.</li>
     *   <li>Frontend / browser: JWT (gateway-propagated). Preserves existing
     *       schema-explorer + {@code useReportSchemaContext} hook access.</li>
     * </ul>
     *
     * <p>Empty configured key (test/dev profile) means internal-key check
     * passes for any caller; production deployments set the key via
     * Vault / ESO so internal callers MUST present it.
     */
    @GetMapping("/snapshot")
    public ResponseEntity<SchemaSnapshot> getSnapshot(
            @RequestParam(required = false) String schema,
            @RequestHeader(value = INTERNAL_API_KEY_HEADER, required = false) String providedKey,
            @AuthenticationPrincipal Jwt jwt) {

        // Auth: internal key OR valid JWT (Codex iter-2 §1 absorb).
        // Empty configured key = internal path open (test/dev passthrough).
        boolean internalOk = snapshotInternalApiKey == null
                || snapshotInternalApiKey.isBlank()
                || snapshotInternalApiKey.equals(providedKey);
        boolean jwtOk = jwt != null;

        if (!internalOk && !jwtOk) {
            return ResponseEntity.status(401).build();
        }

        String target = schema != null ? schema : defaultSchema;
        SchemaSnapshot snapshot = snapshotService.buildSnapshot(target);
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(cacheTtlMinutes, TimeUnit.MINUTES))
            .body(snapshot);
    }

    /**
     * Adım 12 reporting refactor — target contract endpoint for the
     * etl-worker schema-service consumer.
     *
     * <p>Codex {@code 019e2d64} plan-time AGREE (Opt-B′): a NEW endpoint,
     * deliberately separate from {@code /snapshot}. The legacy endpoint
     * keeps emitting {@link SchemaSnapshot} (camelCase, {@code tables}
     * map, column {@code dataType}) for the frontend + report-service;
     * this one emits the narrow {@link ReportingContractSnapshot}
     * (snake_case, {@code tables} list, column {@code type}, allowlist
     * provenance) the etl-worker parser fails closed on.
     *
     * <p>Auth: service-to-service internal-key path only. Unlike
     * {@code /snapshot} this endpoint does NOT accept a browser JWT —
     * it is a migration/ops contract, not a UI surface. Empty configured
     * key (test/dev) keeps the existing passthrough.
     *
     * <p>Fail-closed: when no {@code ReportingAllowlist} table is present
     * in the target schema the response is {@code 404}, not a {@code 200}
     * with an empty {@code tables} list (Codex {@code 019e2d64} S2 trap:
     * a deceptive empty-but-OK response would let the worker report
     * {@code EX_OK} having migrated nothing). {@code 404} maps to the
     * worker's {@code EX_SOFTWARE=70} terminal exit.
     */
    @GetMapping("/reporting-contract")
    public ResponseEntity<ReportingContractSnapshot> getReportingContract(
            @RequestParam(required = false) String schema,
            @RequestHeader(value = INTERNAL_API_KEY_HEADER, required = false) String providedKey) {

        // Internal-key only (no JWT branch — see Javadoc). Empty
        // configured key = passthrough for test/dev profiles.
        boolean internalOk = snapshotInternalApiKey == null
                || snapshotInternalApiKey.isBlank()
                || snapshotInternalApiKey.equals(providedKey);
        if (!internalOk) {
            return ResponseEntity.status(401).build();
        }

        String target = schema != null ? schema : defaultSchema;
        ReportingContractSnapshot contract = reportingContractService.buildContract(target);

        if (contract.tables().isEmpty()) {
            // Fail-closed: no allowlisted table in this schema. 404 →
            // worker EX_SOFTWARE=70 (terminal). The operator must fix
            // the schema target or the allowlist, not retry.
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(cacheTtlMinutes, TimeUnit.MINUTES))
            .body(contract);
    }

    /**
     * Single table detail with columns and relationships.
     */
    @GetMapping("/tables/{tableName}")
    public ResponseEntity<Map<String, Object>> getTable(
            @PathVariable String tableName,
            @RequestParam(required = false) String schema) {
        String target = schema != null ? schema : defaultSchema;
        SchemaSnapshot snapshot = snapshotService.buildSnapshot(target);

        TableInfo table = snapshot.tables().get(tableName);
        if (table == null) {
            return ResponseEntity.notFound().build();
        }

        List<Relationship> outgoing = snapshot.relationships().stream()
            .filter(r -> r.fromTable().equals(tableName))
            .toList();

        List<Relationship> incoming = snapshot.relationships().stream()
            .filter(r -> r.toTable().equals(tableName))
            .toList();

        String domain = snapshot.domains().entrySet().stream()
            .filter(e -> e.getValue().contains(tableName))
            .map(Map.Entry::getKey)
            .findFirst().orElse(null);

        return ResponseEntity.ok(Map.of(
            "table", table,
            "outgoingFks", outgoing,
            "incomingRefs", incoming,
            "domain", domain != null ? domain : ""
        ));
    }

    /**
     * Column search — find which tables contain a given column name.
     */
    @GetMapping("/search/columns")
    public ResponseEntity<Map<String, Object>> searchColumns(
            @RequestParam String q,
            @RequestParam(required = false) String schema) {
        String target = schema != null ? schema : defaultSchema;
        SchemaSnapshot snapshot = snapshotService.buildSnapshot(target);
        String query = q.toUpperCase();

        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();

        for (var entry : snapshot.tables().entrySet()) {
            for (var col : entry.getValue().columns()) {
                if (col.name().toUpperCase().contains(query)) {
                    grouped.computeIfAbsent(col.name(), k -> new ArrayList<>())
                        .add(Map.of(
                            "table", entry.getKey(),
                            "column", col.name(),
                            "type", col.dataType(),
                            "pk", col.pk()
                        ));
                }
            }
        }

        // Sort by number of tables containing the column
        List<Map<String, Object>> results = grouped.entrySet().stream()
            .sorted((a, b) -> b.getValue().size() - a.getValue().size())
            .limit(20)
            .map(e -> Map.<String, Object>of(
                "column", e.getKey(),
                "tableCount", e.getValue().size(),
                "tables", e.getValue()
            ))
            .toList();

        return ResponseEntity.ok(Map.of(
            "query", q,
            "totalMatches", grouped.values().stream().mapToInt(List::size).sum(),
            "results", results
        ));
    }

    /**
     * Impact analysis — what tables are affected if a column/table changes.
     */
    @GetMapping("/impact/{tableName}")
    public ResponseEntity<Map<String, Object>> getImpact(
            @PathVariable String tableName,
            @RequestParam(defaultValue = "2") int hops,
            @RequestParam(required = false) String schema) {
        String target = schema != null ? schema : defaultSchema;
        SchemaSnapshot snapshot = snapshotService.buildSnapshot(target);

        if (!snapshot.tables().containsKey(tableName)) {
            return ResponseEntity.notFound().build();
        }

        // BFS to find N-hop affected tables
        Set<String> affected = new LinkedHashSet<>();
        Set<String> frontier = Set.of(tableName);

        for (int i = 0; i < hops; i++) {
            Set<String> next = new HashSet<>();
            for (Relationship rel : snapshot.relationships()) {
                if (frontier.contains(rel.toTable()) && !affected.contains(rel.fromTable())) {
                    affected.add(rel.fromTable());
                    next.add(rel.fromTable());
                }
                if (frontier.contains(rel.fromTable()) && !affected.contains(rel.toTable())) {
                    affected.add(rel.toTable());
                    next.add(rel.toTable());
                }
            }
            frontier = next;
        }

        affected.remove(tableName);

        return ResponseEntity.ok(Map.of(
            "table", tableName,
            "hops", hops,
            "affectedCount", affected.size(),
            "affectedTables", affected
        ));
    }

    /**
     * Domain list with table counts.
     */
    @GetMapping("/domains")
    public ResponseEntity<Map<String, Object>> getDomains(
            @RequestParam(required = false) String schema) {
        String target = schema != null ? schema : defaultSchema;
        SchemaSnapshot snapshot = snapshotService.buildSnapshot(target);

        List<Map<String, Object>> domains = snapshot.domains().entrySet().stream()
            .sorted((a, b) -> b.getValue().size() - a.getValue().size())
            .map(e -> Map.<String, Object>of(
                "name", e.getKey(),
                "tableCount", e.getValue().size(),
                "tables", e.getValue()
            ))
            .toList();

        return ResponseEntity.ok(Map.of(
            "domainCount", domains.size(),
            "domains", domains
        ));
    }

    /**
     * Hub tables — most referenced tables.
     */
    @GetMapping("/hubs")
    public ResponseEntity<List<SchemaSnapshot.HubTable>> getHubs(
            @RequestParam(required = false) String schema) {
        String target = schema != null ? schema : defaultSchema;
        SchemaSnapshot snapshot = snapshotService.buildSnapshot(target);
        return ResponseEntity.ok(snapshot.analysis().hubTables());
    }

    /**
     * Find join path between two tables.
     * GET /api/v1/schema/path?from=INVOICE&to=EMPLOYEES
     */
    @GetMapping("/path")
    public ResponseEntity<?> findPath(
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(defaultValue = "3") int limit,
            @RequestParam(required = false) String schema) {
        String target = schema != null ? schema : defaultSchema;
        SchemaSnapshot snapshot = snapshotService.buildSnapshot(target);

        if (!snapshot.tables().containsKey(from)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Table not found: " + from));
        }
        if (!snapshot.tables().containsKey(to)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Table not found: " + to));
        }

        var singlePath = pathFinderService.findPath(from, to, snapshot.relationships());
        var paths = singlePath.hops() >= 0 ? List.of(singlePath) : List.<PathFinderService.PathResult>of();
        return ResponseEntity.ok(Map.of(
            "from", from,
            "to", to,
            "pathCount", paths.size(),
            "paths", paths
        ));
    }

    /**
     * Schema health score and quality analysis.
     */
    @GetMapping("/health-score")
    public ResponseEntity<SchemaHealthService.HealthReport> getHealthScore(
            @RequestParam(required = false) String schema) {
        String target = schema != null ? schema : defaultSchema;
        SchemaSnapshot snapshot = snapshotService.buildSnapshot(target);
        return ResponseEntity.ok(healthService.evaluate(snapshot));
    }

    /**
     * Schema drift detection — compare current with previous snapshot.
     */
    @GetMapping("/drift")
    public ResponseEntity<SchemaDriftService.DriftReport> getDrift(
            @RequestParam(required = false) String schema) {
        String target = schema != null ? schema : defaultSchema;
        SchemaSnapshot snapshot = snapshotService.buildSnapshot(target);
        return ResponseEntity.ok(driftService.computeDrift(snapshot, target));
    }

    /**
     * Schema snapshot history timeline.
     */
    @GetMapping("/drift/history")
    public ResponseEntity<?> getDriftHistory(
            @RequestParam(required = false) String schema) {
        String target = schema != null ? schema : defaultSchema;
        return ResponseEntity.ok(Map.of(
            "schema", target,
            "snapshots", driftService.getHistory(target)
        ));
    }

    /**
     * Smart query suggestions for a table.
     */
    @GetMapping("/suggestions/{tableName}")
    public ResponseEntity<List<QuerySuggestionService.QuerySuggestion>> getQuerySuggestions(
            @PathVariable String tableName,
            @RequestParam(required = false) String schema) {
        String target = schema != null ? schema : defaultSchema;
        SchemaSnapshot snapshot = snapshotService.buildSnapshot(target);
        if (!snapshot.tables().containsKey(tableName)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(querySuggestionService.suggest(tableName, snapshot));
    }

    /**
     * FK Lookup — resolve foreign key IDs to display values.
     *
     * Example: /api/v1/schema/lookup?table=EMPLOYEES&ids=1,2,3&displayCol=NAME&schema=dbo
     * Returns: { table, pkColumn, displayColumn, values: { "1": "Ahmet", "2": "Mehmet" } }
     */
    @GetMapping("/lookup")
    public ResponseEntity<Map<String, Object>> lookupValues(
            @RequestParam String table,
            @RequestParam List<String> ids,
            @RequestParam(required = false) String displayCol,
            @RequestParam(required = false) String schema) {
        if (ids == null || ids.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "ids parameter is required"));
        }
        try {
            Map<String, Object> result = lookupService.lookupValues(table, ids, displayCol, schema);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * List all available schemas with table counts.
     */
    @GetMapping("/schemas")
    public ResponseEntity<List<Map<String, Object>>> listSchemas() {
        return ResponseEntity.ok(extractService.listSchemas());
    }
}

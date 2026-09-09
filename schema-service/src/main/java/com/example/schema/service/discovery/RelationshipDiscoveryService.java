package com.example.schema.service.discovery;

import com.example.schema.model.ColumnInfo;
import com.example.schema.model.ForeignKeyInfo;
import com.example.schema.model.Relationship;
import com.example.schema.model.TableInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class RelationshipDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(RelationshipDiscoveryService.class);

    /**
     * Phase 1 portability (Codex 019e2d7d AGREE — quick win 3+4/5): the
     * FK-heuristic dictionaries are loaded from JSON instead of being
     * hard-coded. Defaults stay Workcube-compatible; a new ERP supplies
     * its own files via {@code schema.fk-heuristics.alias-path} /
     * {@code common-fk-path}. See {@link FkHeuristicMapLoader} for the
     * fallback contract.
     */
    static final String DEFAULT_ALIAS_RESOURCE = "classpath:fk-heuristics/aliases-workcube.json";
    static final String DEFAULT_COMMON_FK_RESOURCE = "classpath:fk-heuristics/common-fk-workcube.json";

    private static final Pattern JOIN_PATTERN = Pattern.compile(
        "(\\w+)\\.(\\w+)\\s*=\\s*(\\w+)\\.(\\w+)", Pattern.CASE_INSENSITIVE
    );

    /** Technique 3: alias column → table dictionary (config-driven). */
    private final Map<String, String> aliasMap;

    /** Technique 4: common-FK column → table dictionary (config-driven). */
    private final Map<String, String> commonFkMap;

    @Value("${schema.discovery.enable-view-parsing:true}")
    private boolean enableViewParsing;

    @Autowired
    public RelationshipDiscoveryService(
            FkHeuristicMapLoader loader,
            @Value("${schema.fk-heuristics.alias-path:}") String aliasPath,
            @Value("${schema.fk-heuristics.common-fk-path:}") String commonFkPath) {
        this(loader.load(aliasPath, DEFAULT_ALIAS_RESOURCE, "alias"),
             loader.load(commonFkPath, DEFAULT_COMMON_FK_RESOURCE, "common-fk"));
    }

    /**
     * Test-friendly constructor — injects the heuristic maps directly,
     * bypassing JSON loading. Package-private; production wiring uses the
     * {@link FkHeuristicMapLoader}-based constructor above.
     */
    RelationshipDiscoveryService(Map<String, String> aliasMap, Map<String, String> commonFkMap) {
        this.aliasMap = Map.copyOf(aliasMap);
        this.commonFkMap = Map.copyOf(commonFkMap);
    }

    public List<Relationship> discoverAll(Map<String, TableInfo> tables,
                                          Map<String, String> viewDefinitions) {
        return discoverAll(tables, viewDefinitions, List.of());
    }

    /**
     * Phase B1-2 (Codex 019e2d7d, ADR-0020 §2.4): authoritative-FK compat
     * overload. Single-column foreign keys are mirrored into the heuristic
     * relationship list as {@code source="fk_constraint"},
     * {@code confidence=1.0}; composite FKs are NOT flattened here (the
     * dedup key carries no {@code toColumn}) — they stay in the
     * {@code ForeignKeyInfo} authoritative inventory only.
     */
    public List<Relationship> discoverAll(Map<String, TableInfo> tables,
                                          Map<String, String> viewDefinitions,
                                          List<ForeignKeyInfo> authoritativeForeignKeys) {
        Set<String> tableNames = tables.keySet();
        List<Relationship> all = new ArrayList<>();

        // Technique 1-2: Name match
        all.addAll(discoverByNameMatch(tables, tableNames));

        // Technique 3: Alias patterns
        all.addAll(discoverByAlias(tables, tableNames));

        // Technique 4: Common FK patterns
        all.addAll(discoverByCommonFKs(tables, tableNames));

        // Technique 7: View/SP parsing
        if (enableViewParsing && viewDefinitions != null) {
            all.addAll(discoverFromViewDefinitions(viewDefinitions, tableNames));
        }

        // B1-2: authoritative single-column FK compatibility layer
        all.addAll(toCompatRelationships(authoritativeForeignKeys, tableNames));

        // Deduplicate and score
        return deduplicateAndScore(all);
    }

    /**
     * Converts single-column authoritative FKs into compatibility
     * {@link Relationship}s ({@code source="fk_constraint"},
     * {@code confidence=1.0}). Skipped (kept in the {@code ForeignKeyInfo}
     * inventory only):
     * <ul>
     *   <li>composite FKs — the dedup key ({@code fromTable|fromColumn|
     *       toTable}) cannot represent a multi-column join;</li>
     *   <li>FKs whose {@code fromTable} or {@code toTable} is not in this
     *       snapshot's {@code tables} (Codex 019e2d7d REVISE) — a
     *       cross-schema FK target would otherwise inject a node outside
     *       the relationship graph, skewing impact BFS / path finder /
     *       health orphan counts.</li>
     * </ul>
     */
    private List<Relationship> toCompatRelationships(List<ForeignKeyInfo> foreignKeys,
                                                     Set<String> tableNames) {
        List<Relationship> rels = new ArrayList<>();
        if (foreignKeys == null) {
            return rels;
        }
        for (ForeignKeyInfo fk : foreignKeys) {
            if (fk.isComposite() || fk.fromColumns().isEmpty() || fk.toColumns().isEmpty()) {
                continue;
            }
            if (!tableNames.contains(fk.fromTable()) || !tableNames.contains(fk.toTable())) {
                continue;
            }
            rels.add(new Relationship(
                fk.fromTable(), fk.fromColumns().get(0),
                fk.toTable(), fk.toColumns().get(0),
                1.0, "fk_constraint"));
        }
        log.info("Authoritative FK compat: {} single-column in-snapshot relationships", rels.size());
        return rels;
    }

    private List<Relationship> discoverByNameMatch(Map<String, TableInfo> tables, Set<String> tableNames) {
        List<Relationship> rels = new ArrayList<>();
        for (var entry : tables.entrySet()) {
            String tableName = entry.getKey();
            for (ColumnInfo col : entry.getValue().columns()) {
                if (!col.name().endsWith("_ID") || col.name().equals("ID")) continue;
                String base = col.name().substring(0, col.name().length() - 3);

                if (tableNames.contains(base) && !base.equals(tableName)) {
                    rels.add(new Relationship(tableName, col.name(), base, col.name(), 0.85, "name_match_exact"));
                } else if (tableNames.contains(base + "S") && !(base + "S").equals(tableName)) {
                    rels.add(new Relationship(tableName, col.name(), base + "S", col.name(), 0.80, "name_match_plural"));
                }
            }
        }
        log.info("Name match: {} relationships", rels.size());
        return rels;
    }

    private List<Relationship> discoverByAlias(Map<String, TableInfo> tables, Set<String> tableNames) {
        List<Relationship> rels = new ArrayList<>();
        for (var entry : tables.entrySet()) {
            for (ColumnInfo col : entry.getValue().columns()) {
                String target = aliasMap.get(col.name());
                if (target != null && tableNames.contains(target) && !target.equals(entry.getKey())) {
                    rels.add(new Relationship(entry.getKey(), col.name(), target, col.name(), 0.90, "alias_pattern"));
                }
            }
        }
        log.info("Alias patterns: {} relationships", rels.size());
        return rels;
    }

    private List<Relationship> discoverByCommonFKs(Map<String, TableInfo> tables, Set<String> tableNames) {
        List<Relationship> rels = new ArrayList<>();
        for (var entry : tables.entrySet()) {
            for (ColumnInfo col : entry.getValue().columns()) {
                String target = commonFkMap.get(col.name());
                if (target != null && tableNames.contains(target) && !target.equals(entry.getKey())) {
                    rels.add(new Relationship(entry.getKey(), col.name(), target, col.name(), 0.92, "common_fk"));
                }
            }
        }
        log.info("Common FKs: {} relationships", rels.size());
        return rels;
    }

    private List<Relationship> discoverFromViewDefinitions(Map<String, String> viewDefs, Set<String> tableNames) {
        List<Relationship> rels = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (var entry : viewDefs.entrySet()) {
            if (entry.getValue() == null) continue;
            // One upper-casing and one alias scan per view, not one per JOIN
            // match. The previous shape re-upper-cased the whole definition and
            // scanned it once per known table for every match: on the IFS ERP
            // dictionary (10,886 objects, 18MB of view text) that is billions of
            // character comparisons and pinned the pod's CPU limit until the
            // liveness probe killed it (measured live, exit 143).
            // Locale.ROOT, deliberately: on a Turkish-locale JVM the default
            // toUpperCase() turns 'i' into U+0130 (dotted capital I), which is
            // outside [A-Z] and \w, so every identifier containing an 'i' stops
            // matching and this parser silently finds nothing. Caught by running
            // the tests on a tr_TR machine.
            String upper = entry.getValue().toUpperCase(Locale.ROOT);
            Map<String, String> aliases = aliasMapFor(upper, tableNames);
            Matcher m = JOIN_PATTERN.matcher(upper);
            while (m.find()) {
                String t1 = m.group(1);
                String c1 = m.group(2);
                String t2 = m.group(3);
                String c2 = m.group(4);

                // Resolve to actual table names
                String resolved1 = resolveAlias(t1, aliases, tableNames);
                String resolved2 = resolveAlias(t2, aliases, tableNames);
                if (resolved1 == null || resolved2 == null || resolved1.equals(resolved2)) continue;

                String key = resolved1 + "." + c1 + "=" + resolved2 + "." + c2;
                if (seen.add(key)) {
                    rels.add(new Relationship(resolved1, c1, resolved2, c2, 0.88,
                        "view_parse:" + entry.getKey()));
                }
            }
        }
        log.info("View parsing: {} relationships from {} definitions", rels.size(), viewDefs.size());
        return rels;
    }

    /**
     * {@code <table> <alias>} and {@code <table> AS <alias>} pairs in one pass.
     * The alias is captured inside a lookahead so consecutive pairs may share a
     * word ({@code FROM A a JOIN B b}: the match for {@code A a} must not consume
     * {@code a} before {@code JOIN B b} is considered). Word boundaries also
     * close a hole the substring search had: {@code PREFIX_X co} used to resolve
     * alias {@code co} to table {@code X}.
     */
    private static final Pattern ALIAS_DEF_PATTERN = Pattern.compile(
        "\\b([A-Z0-9_$#]+)\\s+(?:AS\\s+)?(?=([A-Z0-9_$#]+)\\b)");

    private static Map<String, String> aliasMapFor(String upperSql, Set<String> tableNames) {
        Map<String, String> aliases = new HashMap<>();
        Matcher m = ALIAS_DEF_PATTERN.matcher(upperSql);
        while (m.find()) {
            if (tableNames.contains(m.group(1))) {
                aliases.putIfAbsent(m.group(2), m.group(1));
            }
        }
        return aliases;
    }

    private static String resolveAlias(String alias, Map<String, String> aliases, Set<String> tableNames) {
        if (tableNames.contains(alias)) return alias;
        return aliases.get(alias);
    }

    private List<Relationship> deduplicateAndScore(List<Relationship> all) {
        Map<String, List<Relationship>> grouped = new LinkedHashMap<>();
        for (Relationship rel : all) {
            String key = rel.fromTable() + "|" + rel.fromColumn() + "|" + rel.toTable();
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(rel);
        }

        List<Relationship> deduped = new ArrayList<>();
        for (var entry : grouped.entrySet()) {
            List<Relationship> rels = entry.getValue();
            Relationship best = rels.stream()
                .max(Comparator.comparingDouble(Relationship::confidence))
                .orElse(rels.getFirst());

            // B1-2 (Codex 019e2d7d guardrail): TreeSet → deterministic
            // source ordering for the String.join below.
            Set<String> sources = rels.stream()
                .map(r -> r.source().split(":")[0])
                .collect(Collectors.toCollection(TreeSet::new));

            double conf = best.confidence();
            boolean multi = sources.size() > 1;
            if (multi) conf = Math.min(1.0, conf + 0.05 * (sources.size() - 1));

            deduped.add(new Relationship(
                best.fromTable(), best.fromColumn(), best.toTable(), best.toColumn(),
                conf, multi ? String.join("+", sources) : best.source(), multi
            ));
        }

        deduped.sort(Comparator.comparingDouble(Relationship::confidence).reversed()
            .thenComparing(Relationship::fromTable));

        log.info("Dedup: {} raw -> {} unique ({} multi-source)", all.size(), deduped.size(),
            deduped.stream().filter(Relationship::multiSource).count());
        return deduped;
    }
}

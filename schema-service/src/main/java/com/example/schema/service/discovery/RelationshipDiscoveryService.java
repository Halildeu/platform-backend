package com.example.schema.service.discovery;

import com.example.schema.model.ColumnInfo;
import com.example.schema.model.Relationship;
import com.example.schema.model.TableInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class RelationshipDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(RelationshipDiscoveryService.class);

    // Technique 3: Alias mappings
    private static final Map<String, String> ALIAS_MAP = Map.ofEntries(
        Map.entry("COMP_ID", "COMPANY"), Map.entry("CMP_ID", "COMPANY"),
        Map.entry("ACC_COMPANY_ID", "COMPANY"), Map.entry("SALES_COMPANY_ID", "COMPANY"),
        Map.entry("TO_COMPANY_ID", "COMPANY"), Map.entry("CARRIER_COMPANY_ID", "COMPANY"),
        Map.entry("FUEL_COMPANY_ID", "COMPANY"), Map.entry("OLD_COMPANY_ID", "COMPANY"),
        Map.entry("FROM_CMP_ID", "COMPANY"), Map.entry("TO_CMP_ID", "COMPANY"),
        Map.entry("CH_COMPANY_ID", "COMPANY"), Map.entry("SUP_COMPANY_ID", "COMPANY"),
        Map.entry("OUR_CMP_ID", "OUR_COMPANY"),
        Map.entry("EMP_ID", "EMPLOYEES"), Map.entry("ACC_EMPLOYEE_ID", "EMPLOYEES"),
        Map.entry("BASKET_EMPLOYEE_ID", "EMPLOYEES"), Map.entry("CC_EMP_ID", "EMPLOYEES"),
        Map.entry("VALID_EMPLOYEE_ID", "EMPLOYEES"), Map.entry("SSK_EMPLOYEE_ID", "EMPLOYEES"),
        Map.entry("RESP_EMP_ID", "EMPLOYEES"), Map.entry("DATA_OFFICER_ID", "EMPLOYEES"),
        Map.entry("PROJECT_EMP_ID", "EMPLOYEES"), Map.entry("SERVICE_EMPLOYEE_ID", "EMPLOYEES"),
        Map.entry("MANAGER_ID", "EMPLOYEES"), Map.entry("SECOND_BOSS_ID", "EMPLOYEES"),
        Map.entry("THIRD_BOSS_ID", "EMPLOYEES"), Map.entry("FOURTH_BOSS_ID", "EMPLOYEES"),
        Map.entry("FIFTH_BOSS_ID", "EMPLOYEES"), Map.entry("FIRST_BOSS_ID", "EMPLOYEES"),
        Map.entry("DEPT_ID", "DEPARTMENT"), Map.entry("ACC_DEPARTMENT_ID", "DEPARTMENT"),
        Map.entry("ACC_BRANCH_ID", "BRANCH"), Map.entry("RELATED_BRANCH_ID", "BRANCH"),
        Map.entry("ACC_PROJECT_ID", "PRO_PROJECTS"), Map.entry("ROW_PROJECT_ID", "PRO_PROJECTS"),
        Map.entry("OUTSRC_PARTNER_ID", "COMPANY_PARTNER"), Map.entry("SUP_PARTNER_ID", "COMPANY_PARTNER"),
        Map.entry("MANAGER_PARTNER_ID", "COMPANY_PARTNER"), Map.entry("AUTHOR_PARTNER_ID", "COMPANY_PARTNER"),
        Map.entry("OUTSRC_CONSUMER_ID", "CONSUMER"), Map.entry("SUP_CONSUMER_ID", "CONSUMER"),
        Map.entry("RELATED_CONSUMER_ID", "CONSUMER"), Map.entry("ACC_CONSUMER_ID", "CONSUMER"),
        Map.entry("EMPAPP_ID", "EMPLOYEES_APP_EDU_INFO"), Map.entry("CARD_CAT_ID", "SETUP_PROCESS_CAT")
    );

    // Technique 4: Common FK patterns
    private static final Map<String, String> COMMON_FK_MAP = Map.ofEntries(
        Map.entry("COMPANY_ID", "COMPANY"), Map.entry("EMPLOYEE_ID", "EMPLOYEES"),
        Map.entry("OUR_COMPANY_ID", "OUR_COMPANY"), Map.entry("BRANCH_ID", "BRANCH"),
        Map.entry("PROJECT_ID", "PRO_PROJECTS"), Map.entry("CONSUMER_ID", "CONSUMER"),
        Map.entry("DEPARTMENT_ID", "DEPARTMENT"), Map.entry("PARTNER_ID", "COMPANY_PARTNER"),
        Map.entry("PERIOD_ID", "SETUP_DUTY_PERIOD"), Map.entry("EXPENSE_ITEM_ID", "EXPENSE_ITEMS"),
        Map.entry("EXPENSE_CENTER_ID", "EXPENSE_CENTER"), Map.entry("PAYMETHOD_ID", "SETUP_PAYMETHOD"),
        Map.entry("COMMETHOD_ID", "SETUP_COMMETHOD"), Map.entry("ACTIVITY_TYPE_ID", "SETUP_ACTIVITY_TYPES"),
        Map.entry("ORGANIZATION_STEP_ID", "SETUP_ORGANIZATION_STEPS"),
        Map.entry("OFFTIMECAT_ID", "SETUP_OFFTIME"), Map.entry("TRAINING_ID", "TRAINING"),
        Map.entry("TRAINING_CAT_ID", "TRAINING_CAT"), Map.entry("SURVEY_MAIN_ID", "SURVEY_MAIN"),
        Map.entry("PROCESS_CAT_ID", "SETUP_PROCESS_CAT"), Map.entry("STOCK_ID", "SETUP_STOCK_AMOUNT"),
        Map.entry("PRODUCT_ID", "PRODUCTS"), Map.entry("UNIT_ID", "SETUP_UNIT"),
        Map.entry("BRAND_ID", "SETUP_BRAND"), Map.entry("MONEY_ID", "SETUP_MONEY")
    );

    private static final Pattern JOIN_PATTERN = Pattern.compile(
        "(\\w+)\\.(\\w+)\\s*=\\s*(\\w+)\\.(\\w+)", Pattern.CASE_INSENSITIVE
    );

    @Value("${schema.discovery.enable-view-parsing:true}")
    private boolean enableViewParsing;

    public List<Relationship> discoverAll(Map<String, TableInfo> tables,
                                          Map<String, String> viewDefinitions) {
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

        // Deduplicate and score
        return deduplicateAndScore(all);
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
                String target = ALIAS_MAP.get(col.name());
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
                String target = COMMON_FK_MAP.get(col.name());
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
            Matcher m = JOIN_PATTERN.matcher(entry.getValue());
            while (m.find()) {
                String t1 = m.group(1).toUpperCase();
                String c1 = m.group(2).toUpperCase();
                String t2 = m.group(3).toUpperCase();
                String c2 = m.group(4).toUpperCase();

                // Resolve to actual table names
                String resolved1 = resolveAlias(t1, entry.getValue(), tableNames);
                String resolved2 = resolveAlias(t2, entry.getValue(), tableNames);
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

    private String resolveAlias(String alias, String sql, Set<String> tableNames) {
        if (tableNames.contains(alias)) return alias;
        String upper = sql.toUpperCase();
        for (String tbl : tableNames) {
            if (upper.contains(tbl + " " + alias) || upper.contains(tbl + " AS " + alias)) {
                return tbl;
            }
        }
        return null;
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

            Set<String> sources = rels.stream()
                .map(r -> r.source().split(":")[0])
                .collect(Collectors.toSet());

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

package com.example.report.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

@Component
public class DashboardRegistry {

    private static final Logger log = LoggerFactory.getLogger(DashboardRegistry.class);
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_.]*$");

    private static final java.util.Set<String> DANGEROUS_KEYWORDS = java.util.Set.of(
            "DROP", "DELETE", "UPDATE", "INSERT", "EXEC", "EXECUTE", "xp_", "sp_",
            "ALTER", "CREATE", "TRUNCATE", "MERGE"
    );

    private final ConcurrentHashMap<String, DashboardDefinition> definitions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final String definitionsPath;

    public DashboardRegistry(ObjectMapper objectMapper,
                             @Value("${dashboard.definitions-path:classpath:dashboards/}") String definitionsPath) {
        this.objectMapper = objectMapper;
        this.definitionsPath = definitionsPath;
    }

    @PostConstruct
    public void loadDefinitions() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            String pattern = definitionsPath.endsWith("/") ? definitionsPath + "*.json" : definitionsPath + "/*.json";
            Resource[] resources = resolver.getResources(pattern);

            for (Resource resource : resources) {
                try {
                    DashboardDefinition def = objectMapper.readValue(resource.getInputStream(), DashboardDefinition.class);
                    validate(def);
                    definitions.put(def.key(), def);
                    log.info("Loaded dashboard definition: {} ({}) with {} KPIs and {} charts",
                            def.key(), def.title(),
                            def.kpis().size(), def.charts().size());
                } catch (Exception e) {
                    log.error("Failed to load dashboard definition from {}: {}", resource.getFilename(), e.getMessage());
                }
            }

            log.info("Dashboard registry initialized with {} definitions", definitions.size());
        } catch (IOException e) {
            log.warn("Could not scan dashboard definitions directory: {}", e.getMessage());
        }
    }

    public Optional<DashboardDefinition> get(String key) {
        return Optional.ofNullable(definitions.get(key));
    }

    public Collection<DashboardDefinition> getAll() {
        return definitions.values();
    }

    private void validate(DashboardDefinition def) {
        for (KpiDefinition kpi : def.kpis()) {
            validateSource(kpi.source(), kpi.sourceSchema(), "KPI " + kpi.id());
            validateSqlFragment(kpi.aggregate().select(), "KPI " + kpi.id() + " select");
            if (kpi.aggregate().where() != null) {
                validateSqlFragment(kpi.aggregate().where(), "KPI " + kpi.id() + " where");
            }
            if (kpi.trend() != null && kpi.trend().select() != null) {
                validateSqlFragment(kpi.trend().select(), "KPI " + kpi.id() + " trend.select");
            }
        }
        for (ChartDefinition chart : def.charts()) {
            validateSource(chart.source(), chart.sourceSchema(), "Chart " + chart.id());
            validateSqlFragment(chart.aggregate().select(), "Chart " + chart.id() + " select");
            if (chart.aggregate().where() != null) {
                validateSqlFragment(chart.aggregate().where(), "Chart " + chart.id() + " where");
            }
            if (chart.aggregate().groupBy() != null) {
                validateSqlFragment(chart.aggregate().groupBy(), "Chart " + chart.id() + " groupBy");
            }
        }
    }

    private void validateSource(String source, String sourceSchema, String context) {
        if (source == null || !SAFE_IDENTIFIER.matcher(source).matches()) {
            throw new IllegalArgumentException(
                    context + ": source '" + source + "' contains unsafe characters.");
        }
        if (sourceSchema != null && !SAFE_IDENTIFIER.matcher(sourceSchema).matches()) {
            throw new IllegalArgumentException(
                    context + ": sourceSchema '" + sourceSchema + "' contains unsafe characters.");
        }
    }

    private void validateSqlFragment(String fragment, String context) {
        if (fragment == null) return;
        String upper = fragment.toUpperCase();
        for (String keyword : DANGEROUS_KEYWORDS) {
            if (upper.contains(keyword)) {
                throw new IllegalArgumentException(
                        context + ": SQL fragment contains dangerous keyword '" + keyword + "'");
            }
        }
    }
}

package com.example.permission.service;

import com.example.permission.dto.v1.PermissionCatalogDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Codex 019dda1c iter-26: drift guard for the report catalog ↔ dashboard
 * JSON contract.
 *
 * <p>{@link PermissionCatalogService#REPORTS} mirrors the dashboard JSON
 * files at {@code report-service/src/main/resources/dashboards/*.json}.
 * The two are coupled at three levels:
 *
 * <ol>
 *   <li><b>Identity</b> — every JSON {@code key} (kebab-case, e.g.
 *       {@code hr-analytics}) maps to exactly one catalog
 *       {@code ReportCatalogItem.key} (upper-snake, e.g.
 *       {@code HR_ANALYTICS}). Adding a JSON without a catalog row, or
 *       removing a catalog row that still has a JSON, fails this test.</li>
 *   <li><b>Title</b> — JSON {@code title} field equals catalog
 *       {@code label}. UI grouping label depends on this matching.</li>
 *   <li><b>Category</b> — JSON {@code category} field equals catalog
 *       {@code category}. Drawer accordion grouping uses this.</li>
 * </ol>
 *
 * <p>The drawer renders one entry per dashboard. Without this guard a
 * developer can add a new dashboard JSON, ship it to {@code mfe-reporting},
 * and not notice that the role drawer cannot grant access to it because
 * the granule key is missing from the catalog.
 *
 * <p>Resolution path: relative to the {@code permission-service} module
 * root, the dashboards live at {@code ../report-service/src/main/resources/
 * dashboards}. Maven runs tests from the module directory so this is
 * stable.
 */
class PermissionCatalogServiceDashboardSyncTest {

    private static final Path DASHBOARDS_DIR =
            Paths.get("..", "report-service", "src", "main", "resources", "dashboards");

    private final PermissionCatalogService service = new PermissionCatalogService();
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Convert catalog key (UPPER_SNAKE) to dashboard JSON key (kebab-case).
     * E.g. {@code HR_ANALYTICS -> hr-analytics}.
     */
    private static String toJsonKey(String catalogKey) {
        return catalogKey.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    @Test
    void catalogReportSet_matchesDashboardJsonSet_bothDirections() throws IOException {
        assertThat(DASHBOARDS_DIR).exists()
                .as("Dashboard JSON directory must exist relative to permission-service module root");

        Set<String> jsonKeys;
        try (Stream<Path> stream = Files.list(DASHBOARDS_DIR)) {
            jsonKeys = stream
                    .filter(p -> p.toString().endsWith(".json"))
                    .map(this::readJsonKey)
                    .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        }

        Set<String> catalogJsonKeys = service.getCatalog().reports().stream()
                .map(r -> toJsonKey(r.key()))
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));

        assertThat(catalogJsonKeys)
                .as("Every dashboard JSON file must have a catalog row, and vice versa. "
                        + "If you added a JSON, add a ReportCatalogItem in PermissionCatalogService.REPORTS. "
                        + "If you removed a JSON, remove the corresponding catalog row.")
                .containsExactlyInAnyOrderElementsOf(jsonKeys);
    }

    @Test
    void catalogReportTitlesAndCategories_matchDashboardJson() throws IOException {
        Map<String, JsonNode> jsonByKey = readAllDashboardJson();

        for (PermissionCatalogDto.ReportCatalogItem item : service.getCatalog().reports()) {
            String jsonKey = toJsonKey(item.key());
            JsonNode node = jsonByKey.get(jsonKey);
            assertThat(node)
                    .as("Catalog key %s expects dashboard JSON %s", item.key(), jsonKey)
                    .isNotNull();

            assertThat(node.get("title").asText())
                    .as("Catalog label for %s must equal JSON title", item.key())
                    .isEqualTo(item.label());
            assertThat(node.get("category").asText())
                    .as("Catalog category for %s must equal JSON category", item.key())
                    .isEqualTo(item.category());
        }
    }

    @Test
    void catalogReportCategories_areTurkishUserFacing() {
        // Sanity: drawer grouping headers shown to users must be in Turkish.
        // No upper-snake keys, no English fallback.
        Set<String> categories = new HashSet<>();
        for (PermissionCatalogDto.ReportCatalogItem item : service.getCatalog().reports()) {
            categories.add(item.category());
        }
        assertThat(categories).contains("İnsan Kaynakları", "Finans");
    }

    @Test
    void catalogReportModule_isAlwaysReport_forDashboards() {
        // Codex 019dda1c iter-26 contract: dashboard reports are gated by the
        // coarse REPORT module. The fine-grained access flows through the
        // per-key granule (REPORT:HR_ANALYTICS:VIEW). Mixing modules at the
        // catalog level (legacy USER_MANAGEMENT etc.) would defeat the
        // category-grouped drawer UI.
        for (PermissionCatalogDto.ReportCatalogItem item : service.getCatalog().reports()) {
            assertThat(item.module())
                    .as("Catalog module for %s must be REPORT (coarse gate)", item.key())
                    .isEqualTo("REPORT");
        }
    }

    private String readJsonKey(Path path) {
        try {
            JsonNode node = mapper.readTree(path.toFile());
            return node.get("key").asText();
        } catch (IOException e) {
            throw new RuntimeException("Cannot read dashboard JSON: " + path, e);
        }
    }

    private Map<String, JsonNode> readAllDashboardJson() throws IOException {
        Map<String, JsonNode> map = new HashMap<>();
        try (Stream<Path> stream = Files.list(DASHBOARDS_DIR)) {
            stream.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
                try {
                    JsonNode node = mapper.readTree(p.toFile());
                    map.put(node.get("key").asText(), node);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        return map;
    }
}

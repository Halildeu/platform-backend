package com.example.schema.yearly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase 2 Program 2b — YearlySchemaDiscoveryService tests.
 *
 * <p>Codex iter-15 §2b-AGREE absorb (thread 019e0119): regex normalization
 * + deterministic order + filters.
 *
 * <p>Phase 1 portability (Codex 019e2d14 §8 — quick win 2/5): the SQL
 * {@code LIKE} pre-filter and the Java regex are now config-driven. Tests
 * cover (a) the Workcube defaults still apply when config is blank,
 * (b) a non-Workcube ERP pattern works, and (c) invalid regex values
 * fall back to the default instead of failing.
 */
class YearlySchemaDiscoveryServiceTest {

    /** SQL skeleton — mirrors {@link YearlySchemaDiscoveryService#DISCOVERY_SQL}. */
    private static final String SQL = "SELECT name FROM sys.schemas WHERE name LIKE ?";

    /** Workcube default LIKE pattern bound when {@code schema.yearly.like-pattern} is blank. */
    private static final String DEFAULT_LIKE = "workcube_mikrolink_%_%";

    /** Service with blank config → Workcube defaults applied internally. */
    private static YearlySchemaDiscoveryService defaultService(JdbcTemplate jdbc) {
        return new YearlySchemaDiscoveryService(jdbc, "", "");
    }

    // ---------------------------------------------------------------
    // Baseline behaviour (Workcube defaults)
    // ---------------------------------------------------------------

    @Test
    void discover_validYearlyPattern_yieldsParsedDiscoveredSchemas() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(SQL, String.class, DEFAULT_LIKE))
                .thenReturn(List.of(
                        "workcube_mikrolink_2025_1",
                        "workcube_mikrolink_2026_35",
                        "workcube_mikrolink_2024_1"));

        List<YearlySchemaDiscoveryService.DiscoveredSchema> result =
                defaultService(jdbc).discover(List.of(), List.of());

        assertThat(result).hasSize(3);
        // Deterministic ordering: year asc, then companyId asc
        assertThat(result).extracting("name").containsExactly(
                "workcube_mikrolink_2024_1",
                "workcube_mikrolink_2025_1",
                "workcube_mikrolink_2026_35");
    }

    @Test
    void discover_malformedSchemaNames_skippedSilently() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(SQL, String.class, DEFAULT_LIKE))
                .thenReturn(List.of(
                        "workcube_mikrolink",                        // canonical, no year
                        "workcube_mikrolink_1",                       // tenant only, no year
                        "workcube_mikrolink_2025_1",                  // valid
                        "workcube_mikrolink_yyyy_1",                  // non-numeric year
                        "workcube_mikrolink_2025",                    // missing companyId
                        "workcube_mikrolink_2025_abc",                // non-numeric companyId
                        "workcube_mikrolinkV2_2025_1"));              // wrong prefix

        List<YearlySchemaDiscoveryService.DiscoveredSchema> result =
                defaultService(jdbc).discover(List.of(), List.of());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("workcube_mikrolink_2025_1");
        assertThat(result.get(0).year()).isEqualTo(2025);
        assertThat(result.get(0).companyId()).isEqualTo("1");
    }

    @Test
    void discover_yearFilter_appliedAsWhitelist() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(SQL, String.class, DEFAULT_LIKE))
                .thenReturn(List.of(
                        "workcube_mikrolink_2024_1",
                        "workcube_mikrolink_2025_1",
                        "workcube_mikrolink_2026_1"));

        List<YearlySchemaDiscoveryService.DiscoveredSchema> result =
                defaultService(jdbc).discover(List.of(2025, 2026), List.of());

        assertThat(result).hasSize(2);
        assertThat(result).extracting("year").containsExactly(2025, 2026);
    }

    @Test
    void discover_companyFilter_appliedAsWhitelist() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(SQL, String.class, DEFAULT_LIKE))
                .thenReturn(List.of(
                        "workcube_mikrolink_2026_1",
                        "workcube_mikrolink_2026_35",
                        "workcube_mikrolink_2026_99"));

        List<YearlySchemaDiscoveryService.DiscoveredSchema> result =
                defaultService(jdbc).discover(List.of(), List.of("1", "35"));

        assertThat(result).hasSize(2);
        assertThat(result).extracting("companyId").containsExactly("1", "35");
    }

    @Test
    void discover_emptyDb_returnsEmpty() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(SQL, String.class, DEFAULT_LIKE))
                .thenReturn(List.of());

        List<YearlySchemaDiscoveryService.DiscoveredSchema> result =
                defaultService(jdbc).discover(List.of(), List.of());

        assertThat(result).isEmpty();
    }

    // ---------------------------------------------------------------
    // Phase 1 portability — configurable pattern (quick win 2/5)
    // ---------------------------------------------------------------

    @Test
    void discover_blankConfig_appliesWorkcubeDefaults() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        // Blank config must bind the Workcube default LIKE pattern as the
        // SQL parameter and use the Workcube default regex.
        when(jdbc.queryForList(SQL, String.class, DEFAULT_LIKE))
                .thenReturn(List.of("workcube_mikrolink_2026_1"));

        List<YearlySchemaDiscoveryService.DiscoveredSchema> result =
                new YearlySchemaDiscoveryService(jdbc, "  ", "  ")
                        .discover(List.of(), List.of());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).year()).isEqualTo(2026);
        assertThat(result.get(0).companyId()).isEqualTo("1");
    }

    @Test
    void discover_customLikeAndRegex_supportsNonWorkcubeErp() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(SQL, String.class, "acme_erp_%_%"))
                .thenReturn(List.of(
                        "acme_erp_2026_5",
                        "acme_erp_2025_12",
                        "workcube_mikrolink_2026_1"));   // foreign → must NOT match acme regex

        YearlySchemaDiscoveryService svc = new YearlySchemaDiscoveryService(
                jdbc, "acme_erp_%_%", "^acme_erp_(\\d{4})_(\\d+)$");
        List<YearlySchemaDiscoveryService.DiscoveredSchema> result =
                svc.discover(List.of(), List.of());

        assertThat(result).hasSize(2);
        assertThat(result).extracting("name").containsExactly(
                "acme_erp_2025_12", "acme_erp_2026_5");
        assertThat(result.get(0).year()).isEqualTo(2025);
        assertThat(result.get(0).companyId()).isEqualTo("12");
    }

    @Test
    void discover_invalidRegexSyntax_fallsBackToWorkcubeDefault() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(SQL, String.class, DEFAULT_LIKE))
                .thenReturn(List.of("workcube_mikrolink_2026_7"));

        // Unbalanced parentheses → PatternSyntaxException → default regex.
        YearlySchemaDiscoveryService svc =
                new YearlySchemaDiscoveryService(jdbc, "", "^workcube_(((unclosed");
        List<YearlySchemaDiscoveryService.DiscoveredSchema> result =
                svc.discover(List.of(), List.of());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("workcube_mikrolink_2026_7");
    }

    @Test
    void discover_regexWithFewerThanTwoGroups_fallsBackToDefault() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(SQL, String.class, DEFAULT_LIKE))
                .thenReturn(List.of("workcube_mikrolink_2026_9"));

        // Only one capture group → contract violation → default regex.
        YearlySchemaDiscoveryService svc = new YearlySchemaDiscoveryService(
                jdbc, "", "^workcube_mikrolink_(\\d{4})_\\d+$");
        List<YearlySchemaDiscoveryService.DiscoveredSchema> result =
                svc.discover(List.of(), List.of());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).year()).isEqualTo(2026);
        assertThat(result.get(0).companyId()).isEqualTo("9");
    }

    @Test
    void discover_customRegexNonNumericYearGroup_skipsSchemaSafely() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(SQL, String.class, "weird_%_%"))
                .thenReturn(List.of("weird_abc_5"));

        // Regex has 2 groups (contract satisfied) but group(1) is not
        // numeric → that schema is skipped, no exception bubbles up.
        YearlySchemaDiscoveryService svc = new YearlySchemaDiscoveryService(
                jdbc, "weird_%_%", "^weird_([a-z]+)_(\\d+)$");
        List<YearlySchemaDiscoveryService.DiscoveredSchema> result =
                svc.discover(List.of(), List.of());

        assertThat(result).isEmpty();
    }

    @Test
    void discover_invalidRegexWithCustomLike_keepsCustomLikeAndDefaultRegex() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        // The custom LIKE pattern is independent of the regex fallback:
        // an invalid regex resets ONLY the regex to the Workcube default;
        // the custom LIKE pattern is still bound as the SQL parameter.
        when(jdbc.queryForList(SQL, String.class, "acme_erp_%_%"))
                .thenReturn(List.of(
                        "acme_erp_2026_5",             // foreign → default regex misses
                        "workcube_mikrolink_2026_3")); // matches the fallback default regex

        YearlySchemaDiscoveryService svc = new YearlySchemaDiscoveryService(
                jdbc, "acme_erp_%_%", "^workcube_(((unclosed");
        List<YearlySchemaDiscoveryService.DiscoveredSchema> result =
                svc.discover(List.of(), List.of());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("workcube_mikrolink_2026_3");
    }

    @Test
    void discover_customRegexEmptyCompanyGroup_skipsSchemaSafely() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(SQL, String.class, "weird_%_%"))
                .thenReturn(List.of("weird_2026_"));

        // Regex satisfies the 2-group contract but group(2) (companyId)
        // matches empty → schema skipped defensively, no malformed record.
        YearlySchemaDiscoveryService svc = new YearlySchemaDiscoveryService(
                jdbc, "weird_%_%", "^weird_(\\d{4})_(\\d*)$");
        List<YearlySchemaDiscoveryService.DiscoveredSchema> result =
                svc.discover(List.of(), List.of());

        assertThat(result).isEmpty();
    }
}

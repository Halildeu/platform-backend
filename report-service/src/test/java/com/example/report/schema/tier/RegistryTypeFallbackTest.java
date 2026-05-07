package com.example.report.schema.tier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.report.registry.AccessConfig;
import com.example.report.registry.ColumnDefinition;
import com.example.report.registry.ReportDefinition;
import com.example.report.registry.ReportRegistry;
import com.example.report.schema.SchemaTruthLookupContext;
import com.example.report.schema.SchemaTruthLookupPolicy;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Phase 2 Program 8b — RegistryTypeFallback Tier 3 unit tests.
 *
 * <p>Spec §5.1:
 * <ul>
 *   <li>{@code RegistryTypeFallback_extractsFromReportColumnsArray}:
 *       reports/&lt;key&gt;.json columns[] parse + lookup</li>
 *   <li>{@code RegistryTypeFallback_unknownReport_returnsEmpty}: report
 *       registry'de yok → empty (Tier 3 ulaşılır ama miss)</li>
 * </ul>
 */
class RegistryTypeFallbackTest {

    private ReportRegistry mockRegistry;
    private RegistryTypeFallback fallback;

    @BeforeEach
    void setUp() {
        mockRegistry = mock(ReportRegistry.class);
        fallback = new RegistryTypeFallback(mockRegistry);
    }

    @Test
    void extractsFromReportColumnsArray_matchesByFieldName() {
        ReportDefinition def = new ReportDefinition(
                "fin-muhasebe-detay", "1", "Test", "test", "test",
                "ACCOUNT_CARD_ROWS", "workcube_mikrolink_2026_35",
                "yearly", null, null,
                List.of(
                        new ColumnDefinition("AMOUNT", "Tutar", "number", 100, false),
                        new ColumnDefinition("ACCOUNT_CODE", "Hesap", "text", 100, false)),
                null, "ASC",
                new AccessConfig(null, null, null, null));
        when(mockRegistry.get("fin-muhasebe-detay")).thenReturn(Optional.of(def));

        SchemaTruthLookupContext ctx = new SchemaTruthLookupContext(
                "fin-muhasebe-detay", "yearly",
                SchemaTruthLookupPolicy.RUNTIME_DEGRADED_TYPE, "test_consumer");

        Optional<String> result = fallback.lookupColumnType(ctx, "AMOUNT");

        assertThat(result).contains("number");
    }

    @Test
    void unknownReport_returnsEmpty() {
        when(mockRegistry.get("nonexistent-report")).thenReturn(Optional.empty());

        SchemaTruthLookupContext ctx = new SchemaTruthLookupContext(
                "nonexistent-report", "yearly",
                SchemaTruthLookupPolicy.RUNTIME_DEGRADED_TYPE, "test_consumer");

        Optional<String> result = fallback.lookupColumnType(ctx, "AMOUNT");

        assertThat(result).isEmpty();
    }
}

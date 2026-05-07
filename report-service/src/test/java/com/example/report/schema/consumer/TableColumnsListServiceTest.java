package com.example.report.schema.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.report.registry.ReportRegistry;
import com.example.report.schema.SchemaSnapshot;
import com.example.report.schema.SchemaTruthLookupContext;
import com.example.report.schema.SchemaTruthLookupPolicy;
import com.example.report.schema.SchemaTruthService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Phase 2 Program 8c — TableColumnsListService unit tests.
 *
 * <p>Spec §2.1.2 capability matrix: Tier 1+2 OK; Tier 3 partial.
 */
class TableColumnsListServiceTest {

    private SchemaTruthService mockFacade;
    private ReportRegistry mockRegistry;
    private TableColumnsListService service;

    @BeforeEach
    void setUp() {
        mockFacade = mock(SchemaTruthService.class);
        mockRegistry = mock(ReportRegistry.class);
        service = new TableColumnsListService(mockFacade, mockRegistry);
    }

    @Test
    void listColumns_returnsColumns_whenTableInSnapshot() {
        SchemaTruthLookupContext ctx = new SchemaTruthLookupContext(
                "fin-muhasebe-detay", "yearly",
                SchemaTruthLookupPolicy.RUNTIME_DEGRADED_TYPE, "sql_builder_discovery");
        SchemaSnapshot.ColumnInfo amountCol =
                new SchemaSnapshot.ColumnInfo("AMOUNT", "DECIMAL(18,2)", false);
        SchemaSnapshot.TableInfo tableInfo =
                new SchemaSnapshot.TableInfo("ACCOUNT_CARD_ROWS",
                        "workcube_mikrolink_2026_35", List.of(amountCol));
        SchemaSnapshot snapshot = new SchemaSnapshot(Map.of("ACCOUNT_CARD_ROWS", tableInfo));
        when(mockFacade.fetchSnapshot(any(), any())).thenReturn(Optional.of(snapshot));

        List<SchemaSnapshot.ColumnInfo> result =
                service.listColumns(ctx, "workcube_mikrolink_2026_35", "ACCOUNT_CARD_ROWS");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("AMOUNT");
        assertThat(result.get(0).dataType()).isEqualTo("DECIMAL(18,2)");
    }

    @Test
    void listColumns_emptyList_whenSnapshotMissing() {
        SchemaTruthLookupContext ctx = new SchemaTruthLookupContext(
                "fin-muhasebe-detay", "yearly",
                SchemaTruthLookupPolicy.RUNTIME_DEGRADED_TYPE, "sql_builder_discovery");
        when(mockFacade.fetchSnapshot(any(), any())).thenReturn(Optional.empty());

        List<SchemaSnapshot.ColumnInfo> result =
                service.listColumns(ctx, "workcube_unknown", "ACCOUNT_CARD_ROWS");

        assertThat(result).isEmpty();
    }

    @Test
    void listColumns_emptyList_whenTableNotInSnapshot() {
        SchemaTruthLookupContext ctx = new SchemaTruthLookupContext(
                "fin-muhasebe-detay", "yearly",
                SchemaTruthLookupPolicy.RUNTIME_DEGRADED_TYPE, "sql_builder_discovery");
        SchemaSnapshot snapshot = new SchemaSnapshot(Map.of()); // no tables
        when(mockFacade.fetchSnapshot(any(), any())).thenReturn(Optional.of(snapshot));

        List<SchemaSnapshot.ColumnInfo> result =
                service.listColumns(ctx, "workcube_mikrolink_2026_35", "GHOST_TABLE");

        assertThat(result).isEmpty();
    }

    @Test
    void listColumns_failSoft_returnsEmpty() {
        SchemaTruthLookupContext ctx = new SchemaTruthLookupContext(
                "fin-muhasebe-detay", "yearly",
                SchemaTruthLookupPolicy.RUNTIME_DEGRADED_TYPE, "sql_builder_discovery");
        when(mockFacade.fetchSnapshot(any(), any()))
                .thenThrow(new RuntimeException("schema-service unreachable"));

        // Tier 1 fail-soft → empty list (no exception propagation; consumer fail-soft)
        List<SchemaSnapshot.ColumnInfo> result =
                service.listColumns(ctx, "workcube_mikrolink_2026_35", "ACCOUNT_CARD_ROWS");

        assertThat(result).isEmpty();
    }
}

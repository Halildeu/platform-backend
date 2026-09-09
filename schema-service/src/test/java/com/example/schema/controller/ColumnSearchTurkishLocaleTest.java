package com.example.schema.controller;

import com.example.schema.catalog.CatalogReader;
import com.example.schema.catalog.CatalogSourceRegistry;
import com.example.schema.model.ColumnInfo;
import com.example.schema.model.SchemaSnapshot;
import com.example.schema.model.TableInfo;
import com.example.schema.service.PathFinderService;
import com.example.schema.service.QuerySuggestionService;
import com.example.schema.service.ReportingContractService;
import com.example.schema.service.SchemaDriftService;
import com.example.schema.service.SchemaExtractService;
import com.example.schema.service.SchemaHealthService;
import com.example.schema.service.SchemaLookupService;
import com.example.schema.service.SchemaSnapshotService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * gitops#3603 — the Explorer's column search upper-cases both the query and the
 * catalogue names. On a {@code tr_TR} default locale a locale-less
 * {@code toUpperCase()} turns {@code invoice} into {@code İNVOİCE}, which never
 * matches {@code INVOICE_ID}; the search silently returns nothing for every
 * identifier containing an {@code i}. Pinned with the same default-locale swap
 * as {@code ViewAliasResolutionTest}.
 */
class ColumnSearchTurkishLocaleTest {

    private SchemaController controller;
    private Locale previous;

    @BeforeEach
    void setUp() {
        previous = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));

        CatalogReader primary = mock(CatalogReader.class);
        when(primary.sourceId()).thenReturn(CatalogSourceRegistry.PRIMARY_SOURCE_ID);
        when(primary.engine()).thenReturn("mssql");
        when(primary.defaultSchema()).thenReturn("workcube_mikrolink");

        TableInfo invoice = new TableInfo("INVOICE", "dbo", List.of(
                new ColumnInfo("INVOICE_ID", "int", 4, false, true, true, 1),
                new ColumnInfo("TOTAL", "decimal", 9, true, false, false, 2)));
        SchemaSnapshot snapshot = SchemaSnapshot.builder()
                .version("v1")
                .metadata(new SchemaSnapshot.Metadata("mssql", "host", "db", "schema", Instant.now(), 1, 2, 0, 0))
                .tables(Map.of("INVOICE", invoice))
                .relationships(List.of())
                .domains(Map.of())
                .analysis(new SchemaSnapshot.Analysis(List.of(), List.of()))
                .build();
        SchemaSnapshotService snapshotService = mock(SchemaSnapshotService.class);
        when(snapshotService.buildSnapshot(any(), anyString())).thenReturn(snapshot);

        controller = new SchemaController(
                mock(SchemaExtractService.class), snapshotService, mock(SchemaLookupService.class),
                mock(PathFinderService.class), mock(SchemaHealthService.class), mock(SchemaDriftService.class),
                mock(QuerySuggestionService.class), mock(ReportingContractService.class),
                new CatalogSourceRegistry(List.of(primary)));
    }

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(previous);
    }

    @Test
    @SuppressWarnings("unchecked")
    void lowercaseQueryWithLatinIFindsTheColumnUnderATurkishDefaultLocale() {
        assertThat("invoice".toUpperCase()).as("precondition: default locale really is Turkish").isEqualTo("İNVOİCE");

        Map<String, Object> body = controller.searchColumns("invoice", null, null).getBody();

        assertThat(body).isNotNull();
        List<Map<String, Object>> results = (List<Map<String, Object>>) body.get("results");
        assertThat(results).hasSize(1);
        assertThat(results.get(0)).containsEntry("column", "INVOICE_ID");
    }
}

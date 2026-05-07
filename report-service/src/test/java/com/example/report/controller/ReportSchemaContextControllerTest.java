package com.example.report.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.report.registry.AccessConfig;
import com.example.report.registry.ColumnDefinition;
import com.example.report.registry.ReportDefinition;
import com.example.report.registry.ReportRegistry;
import com.example.report.schema.SchemaSnapshot;
import com.example.report.schema.SchemaTruthService;
import com.example.report.schema.tier.CommittedSnapshotLoader;
import com.example.report.schema.tier.SchemaServiceClient;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Phase 2 Program 8e — ReportSchemaContextController unit tests.
 *
 * <p>Spec §2.5: GET /api/v1/reports/{key}/schema-context + X-Schema-Truth-Tier
 * canonical header.
 *
 * <p>Test 4 tier scenarios:
 * <ol>
 *   <li>Tier 1 schema-service success → header schema_service</li>
 *   <li>Tier 1 fail-soft → Tier 2 success → header committed_snapshot</li>
 *   <li>Tier 1 + 2 miss → Tier 3 (registry types) → header registry_type</li>
 *   <li>Unknown report key → 404</li>
 * </ol>
 */
class ReportSchemaContextControllerTest {

    private ReportRegistry mockRegistry;
    private SchemaServiceClient mockSchemaClient;
    private CommittedSnapshotLoader mockLoader;
    private SchemaTruthService mockFacade;
    private ReportSchemaContextController controller;

    @BeforeEach
    void setUp() {
        mockRegistry = mock(ReportRegistry.class);
        mockSchemaClient = mock(SchemaServiceClient.class);
        mockLoader = mock(CommittedSnapshotLoader.class);
        mockFacade = mock(SchemaTruthService.class);
        controller = new ReportSchemaContextController(
                mockRegistry, mockSchemaClient, mockLoader, mockFacade);
    }

    @Test
    void getSchemaContext_unknownReport_returns404() {
        when(mockRegistry.get("ghost")).thenReturn(Optional.empty());

        ResponseEntity<ReportSchemaContextController.SchemaContextResponse> response =
                controller.getSchemaContext("ghost");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getSchemaContext_tier1Success_returnsSchemaServiceTier() {
        ReportDefinition def = buildDef("fin-muhasebe-detay", "ACCOUNT_CARD_ROWS",
                "workcube_mikrolink_2026_35");
        when(mockRegistry.get("fin-muhasebe-detay")).thenReturn(Optional.of(def));

        SchemaSnapshot.ColumnInfo col = new SchemaSnapshot.ColumnInfo("AMOUNT", "DECIMAL(18,2)", false);
        SchemaSnapshot.TableInfo table = new SchemaSnapshot.TableInfo(
                "ACCOUNT_CARD_ROWS", "workcube_mikrolink_2026_35", List.of(col));
        SchemaSnapshot snapshot = new SchemaSnapshot(Map.of("ACCOUNT_CARD_ROWS", table));
        when(mockSchemaClient.fetchSnapshot(any(), any())).thenReturn(Optional.of(snapshot));

        ResponseEntity<ReportSchemaContextController.SchemaContextResponse> response =
                controller.getSchemaContext("fin-muhasebe-detay");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(ReportSchemaContextController.TIER_HEADER))
                .isEqualTo("schema_service");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().tier()).isEqualTo("schema_service");
        assertThat(response.getBody().columnTypes()).containsEntry("AMOUNT", "DECIMAL(18,2)");
    }

    @Test
    void getSchemaContext_tier1FailSoft_tier2Success_returnsCommittedSnapshotTier() {
        ReportDefinition def = buildDef("fin-muhasebe-detay", "ACCOUNT_CARD_ROWS",
                "workcube_mikrolink_2026_35");
        when(mockRegistry.get("fin-muhasebe-detay")).thenReturn(Optional.of(def));

        // Tier 1 throws (schema-service unreachable)
        when(mockSchemaClient.fetchSnapshot(any(), any()))
                .thenThrow(new RuntimeException("schema-service unreachable"));

        // Tier 2 returns committed snapshot
        SchemaSnapshot.ColumnInfo col = new SchemaSnapshot.ColumnInfo("AMOUNT", "DECIMAL(18,2)", false);
        SchemaSnapshot.TableInfo table = new SchemaSnapshot.TableInfo(
                "ACCOUNT_CARD_ROWS", "workcube_mikrolink_2026_35", List.of(col));
        SchemaSnapshot snapshot = new SchemaSnapshot(Map.of("ACCOUNT_CARD_ROWS", table));
        when(mockLoader.lookup(any(), any())).thenReturn(Optional.of(snapshot));

        ResponseEntity<ReportSchemaContextController.SchemaContextResponse> response =
                controller.getSchemaContext("fin-muhasebe-detay");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(ReportSchemaContextController.TIER_HEADER))
                .isEqualTo("committed_snapshot");
        assertThat(response.getBody().columnTypes()).containsEntry("AMOUNT", "DECIMAL(18,2)");
    }

    @Test
    void getSchemaContext_tier1And2Miss_fallsToTier3RegistryType() {
        ReportDefinition def = buildDef("fin-muhasebe-detay", "ACCOUNT_CARD_ROWS",
                "workcube_mikrolink_2026_35");
        when(mockRegistry.get("fin-muhasebe-detay")).thenReturn(Optional.of(def));

        when(mockSchemaClient.fetchSnapshot(any(), any())).thenReturn(Optional.empty());
        when(mockLoader.lookup(any(), any())).thenReturn(Optional.empty());

        ResponseEntity<ReportSchemaContextController.SchemaContextResponse> response =
                controller.getSchemaContext("fin-muhasebe-detay");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(ReportSchemaContextController.TIER_HEADER))
                .isEqualTo("registry_type");
        // Tier 3: registry types → AMOUNT mapped to "number"
        assertThat(response.getBody().columnTypes()).containsEntry("AMOUNT", "number");
    }

    private ReportDefinition buildDef(String key, String table, String schema) {
        return new ReportDefinition(
                key, "1", "Test", "test", "test",
                table, schema,
                "yearly", null, null,
                List.of(
                        new ColumnDefinition("AMOUNT", "Tutar", "number", 100, false),
                        new ColumnDefinition("ACCOUNT_CODE", "Hesap", "text", 100, false)),
                null, "ASC",
                new AccessConfig(null, null, null, null));
    }
}

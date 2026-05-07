package com.example.report.schema.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.report.schema.SchemaSnapshot;
import com.example.report.schema.SchemaTruthLookupContext;
import com.example.report.schema.SchemaTruthLookupPolicy;
import com.example.report.schema.SchemaTruthService;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Phase 2 Program 8c — SchemaExistsService unit tests.
 *
 * <p>Capability matrix §2.1.2: RUNTIME_STRICT_EXISTENCE only;
 * other policies → IllegalArgumentException.
 *
 * <p>Tier 1 fail-soft → exception propagates (caller TenantBoundaryGuard
 * 503 schema_resolver_miss).
 */
class SchemaExistsServiceTest {

    private SchemaTruthService mockFacade;
    private SchemaExistsService service;

    @BeforeEach
    void setUp() {
        mockFacade = mock(SchemaTruthService.class);
        service = new SchemaExistsService(mockFacade);
    }

    @Test
    void exists_strictPolicy_returnsTrue_whenTablesContainMatchingSchema() {
        // Codex iter-1 §1 absorb: schema-service unknown schema 200 + empty tables;
        // exists() schema match içeren TableInfo arar, sadece snapshot.isPresent()
        // false-positive üretir.
        SchemaTruthLookupContext ctx = new SchemaTruthLookupContext(
                "fin-muhasebe-detay", "yearly",
                SchemaTruthLookupPolicy.RUNTIME_STRICT_EXISTENCE, "tenant_boundary_guard");
        SchemaSnapshot.TableInfo table = new SchemaSnapshot.TableInfo(
                "ACCOUNT_CARD_ROWS", "workcube_mikrolink_2026_35",
                java.util.List.of());
        SchemaSnapshot snapshotWithMatch = new SchemaSnapshot(
                Map.of("ACCOUNT_CARD_ROWS", table));
        when(mockFacade.fetchSnapshot(ctx, "workcube_mikrolink_2026_35"))
                .thenReturn(Optional.of(snapshotWithMatch));

        boolean exists = service.exists(ctx, "workcube_mikrolink_2026_35");

        assertThat(exists).isTrue();
        verify(mockFacade).fetchSnapshot(ctx, "workcube_mikrolink_2026_35");
    }

    @Test
    void exists_strictPolicy_returnsFalse_whenSnapshotEmpty() {
        SchemaTruthLookupContext ctx = new SchemaTruthLookupContext(
                "fin-muhasebe-detay", "yearly",
                SchemaTruthLookupPolicy.RUNTIME_STRICT_EXISTENCE, "tenant_boundary_guard");
        when(mockFacade.fetchSnapshot(any(), any())).thenReturn(Optional.empty());

        boolean exists = service.exists(ctx, "workcube_nonexistent");

        assertThat(exists).isFalse();
    }

    @Test
    void exists_strictPolicy_returnsFalse_whenTablesIsEmpty_unknownSchemaCase() {
        // Codex iter-1 §1 absorb: schema-service '/snapshot' unknown schema için
        // 200 + empty tables döner; bu durumun false-positive olmaması için tables
        // boşken false dönmeli.
        SchemaTruthLookupContext ctx = new SchemaTruthLookupContext(
                "fin-muhasebe-detay", "yearly",
                SchemaTruthLookupPolicy.RUNTIME_STRICT_EXISTENCE, "tenant_boundary_guard");
        when(mockFacade.fetchSnapshot(any(), any()))
                .thenReturn(Optional.of(new SchemaSnapshot(Map.of())));

        boolean exists = service.exists(ctx, "workcube_unknown_schema");

        assertThat(exists).isFalse();
    }

    @Test
    void exists_strictPolicy_returnsFalse_whenTablesPresentButSchemaDoesNotMatch() {
        // Eğer snapshot başka schema için döndüyse (boş schema name match yok), false.
        SchemaTruthLookupContext ctx = new SchemaTruthLookupContext(
                "fin-muhasebe-detay", "yearly",
                SchemaTruthLookupPolicy.RUNTIME_STRICT_EXISTENCE, "tenant_boundary_guard");
        SchemaSnapshot.TableInfo otherSchemaTable = new SchemaSnapshot.TableInfo(
                "ACCOUNT_CARD_ROWS", "workcube_mikrolink_2025_35",
                java.util.List.of());
        SchemaSnapshot snapshot = new SchemaSnapshot(Map.of("ACCOUNT_CARD_ROWS", otherSchemaTable));
        when(mockFacade.fetchSnapshot(any(), any())).thenReturn(Optional.of(snapshot));

        boolean exists = service.exists(ctx, "workcube_mikrolink_2026_35");

        assertThat(exists).isFalse();
    }

    @Test
    void exists_tier1FailSoft_propagatesException() {
        SchemaTruthLookupContext ctx = new SchemaTruthLookupContext(
                "fin-muhasebe-detay", "yearly",
                SchemaTruthLookupPolicy.RUNTIME_STRICT_EXISTENCE, "tenant_boundary_guard");
        when(mockFacade.fetchSnapshot(any(), any()))
                .thenThrow(new RuntimeException("schema-service unreachable"));

        assertThatThrownBy(() -> service.exists(ctx, "workcube_mikrolink_2026_35"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void exists_nonStrictPolicy_throwsIllegalArgument() {
        SchemaTruthLookupContext ctx = new SchemaTruthLookupContext(
                "fin-muhasebe-detay", "yearly",
                SchemaTruthLookupPolicy.RUNTIME_DEGRADED_TYPE, "filter_translator");

        assertThatThrownBy(() -> service.exists(ctx, "workcube_mikrolink_2026_35"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RUNTIME_STRICT_EXISTENCE");
    }
}

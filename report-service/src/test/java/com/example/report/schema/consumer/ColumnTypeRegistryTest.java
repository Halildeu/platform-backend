package com.example.report.schema.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.report.schema.SchemaSnapshot;
import com.example.report.schema.SchemaTruthLookupContext;
import com.example.report.schema.SchemaTruthLookupPolicy;
import com.example.report.schema.SchemaTruthService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.ScopeNotActiveException;

/**
 * Phase 2 Program 8c — ColumnTypeRegistry unit tests
 * (Codex iter-1 §2 BLOCKING + §4 absorb).
 *
 * <p>Critical contracts:
 * <ul>
 *   <li>DB-level lookupColumnType: Tier 1+2 primary, Tier 3 fallback only on miss</li>
 *   <li>Tier 1+2 success → returns DB-level dataType (precision-aware)</li>
 *   <li>Tier 1+2 fail-soft (exception) → falls to Tier 3</li>
 *   <li>RequestColumnTypeCache provider unavailable / scope inactive → safe fallback</li>
 * </ul>
 */
class ColumnTypeRegistryTest {

    private SchemaTruthService mockFacade;
    @SuppressWarnings("unchecked")
    private ObjectProvider<RequestColumnTypeCache> mockCacheProvider =
            (ObjectProvider<RequestColumnTypeCache>) mock(ObjectProvider.class);
    private ColumnTypeRegistry registry;

    @BeforeEach
    void setUp() {
        mockFacade = mock(SchemaTruthService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<RequestColumnTypeCache> provider =
                (ObjectProvider<RequestColumnTypeCache>) mock(ObjectProvider.class);
        mockCacheProvider = provider;
        registry = new ColumnTypeRegistry(mockFacade, mockCacheProvider);
    }

    @Test
    void lookupColumnType_dbLevel_returnsTier1DataType_whenSnapshotPresent() {
        SchemaTruthLookupContext ctx = new SchemaTruthLookupContext(
                "fin-muhasebe-detay", "yearly",
                SchemaTruthLookupPolicy.RUNTIME_DEGRADED_TYPE, "filter_translator");
        SchemaSnapshot.ColumnInfo amountCol =
                new SchemaSnapshot.ColumnInfo("AMOUNT", "DECIMAL(18,2)", false);
        SchemaSnapshot.TableInfo table = new SchemaSnapshot.TableInfo(
                "ACCOUNT_CARD_ROWS", "workcube_mikrolink_2026_35", List.of(amountCol));
        SchemaSnapshot snapshot = new SchemaSnapshot(Map.of("ACCOUNT_CARD_ROWS", table));
        when(mockFacade.fetchSnapshot(any(), any())).thenReturn(Optional.of(snapshot));
        when(mockCacheProvider.getIfAvailable()).thenReturn(null); // non-web context

        Optional<String> result = registry.lookupColumnType(ctx,
                "workcube_mikrolink_2026_35", "ACCOUNT_CARD_ROWS", "AMOUNT");

        assertThat(result).contains("DECIMAL(18,2)");
    }

    @Test
    void lookupColumnType_dbLevel_fallsToTier3_whenSnapshotMisses() {
        SchemaTruthLookupContext ctx = new SchemaTruthLookupContext(
                "fin-muhasebe-detay", "yearly",
                SchemaTruthLookupPolicy.RUNTIME_DEGRADED_TYPE, "filter_translator");
        when(mockFacade.fetchSnapshot(any(), any())).thenReturn(Optional.empty());
        when(mockFacade.lookupColumnTypeTier3(any(), any())).thenReturn(Optional.of("number"));
        when(mockCacheProvider.getIfAvailable()).thenReturn(null);

        Optional<String> result = registry.lookupColumnType(ctx,
                "workcube_mikrolink_2026_35", "ACCOUNT_CARD_ROWS", "AMOUNT");

        assertThat(result).contains("number");
    }

    @Test
    void lookupColumnType_dbLevel_fallsToTier3_whenTier1Throws() {
        SchemaTruthLookupContext ctx = new SchemaTruthLookupContext(
                "fin-muhasebe-detay", "yearly",
                SchemaTruthLookupPolicy.RUNTIME_DEGRADED_TYPE, "filter_translator");
        when(mockFacade.fetchSnapshot(any(), any()))
                .thenThrow(new RuntimeException("schema-service unreachable"));
        when(mockFacade.lookupColumnTypeTier3(any(), any())).thenReturn(Optional.of("text"));
        when(mockCacheProvider.getIfAvailable()).thenReturn(null);

        Optional<String> result = registry.lookupColumnType(ctx,
                "workcube_mikrolink_2026_35", "ACCOUNT_CARD_ROWS", "AMOUNT");

        assertThat(result).contains("text");
    }

    @Test
    void lookupReportColumnType_routesToTier3Only() {
        SchemaTruthLookupContext ctx = new SchemaTruthLookupContext(
                "fin-muhasebe-detay", "yearly",
                SchemaTruthLookupPolicy.RUNTIME_DEGRADED_TYPE, "frontend_hook");
        when(mockFacade.lookupColumnTypeTier3(any(), any())).thenReturn(Optional.of("number"));
        when(mockCacheProvider.getIfAvailable()).thenReturn(null);

        Optional<String> result = registry.lookupReportColumnType(ctx, "AMOUNT");

        assertThat(result).contains("number");
    }

    @Test
    void exists_dbLevel_returnsFalse_whenColumnAbsent() {
        SchemaTruthLookupContext ctx = new SchemaTruthLookupContext(
                "fin-muhasebe-detay", "yearly",
                SchemaTruthLookupPolicy.RUNTIME_DEGRADED_TYPE, "contract_validator");
        SchemaSnapshot.ColumnInfo otherCol =
                new SchemaSnapshot.ColumnInfo("OTHER", "INT", false);
        SchemaSnapshot.TableInfo table = new SchemaSnapshot.TableInfo(
                "ACCOUNT_CARD_ROWS", "workcube_mikrolink_2026_35", List.of(otherCol));
        SchemaSnapshot snapshot = new SchemaSnapshot(Map.of("ACCOUNT_CARD_ROWS", table));
        when(mockFacade.fetchSnapshot(any(), any())).thenReturn(Optional.of(snapshot));

        boolean exists = registry.exists(ctx,
                "workcube_mikrolink_2026_35", "ACCOUNT_CARD_ROWS", "AMOUNT");

        assertThat(exists).isFalse();
    }

    @Test
    void cacheProviderScopeInactive_safeFallback_continuesWithoutCache() {
        // Codex iter-1 §4 absorb: ObjectProvider.getIfAvailable() may return
        // a request-scoped proxy that throws ScopeNotActiveException on method
        // invocation in non-web context. ColumnTypeRegistry.safeCache() catches.
        SchemaTruthLookupContext ctx = new SchemaTruthLookupContext(
                "fin-muhasebe-detay", "yearly",
                SchemaTruthLookupPolicy.RUNTIME_DEGRADED_TYPE, "scheduled_job");
        RequestColumnTypeCache faultyCache = mock(RequestColumnTypeCache.class);
        when(faultyCache.size()).thenThrow(
                new ScopeNotActiveException("requestScope", "request",
                        new IllegalStateException("not in request scope")));
        when(mockCacheProvider.getIfAvailable()).thenReturn(faultyCache);
        when(mockFacade.fetchSnapshot(any(), any())).thenReturn(Optional.empty());
        when(mockFacade.lookupColumnTypeTier3(any(), any())).thenReturn(Optional.of("number"));

        // Should not throw — registry safeCache catches ScopeNotActiveException
        Optional<String> result = registry.lookupColumnType(ctx,
                "workcube_mikrolink_2026_35", "ACCOUNT_CARD_ROWS", "AMOUNT");

        assertThat(result).contains("number");
    }
}

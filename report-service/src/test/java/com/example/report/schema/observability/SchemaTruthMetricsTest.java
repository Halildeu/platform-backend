package com.example.report.schema.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.report.schema.SchemaTruthLookupContext;
import com.example.report.schema.SchemaTruthLookupPolicy;
import com.example.report.schema.tier.CommittedSnapshotLoader;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Phase 2 Program 8d — SchemaTruthMetrics unit tests.
 *
 * <p>Spec §2.4 + §9 DoD: 6 metric isim ve cardinality contract.
 */
class SchemaTruthMetricsTest {

    private SimpleMeterRegistry registry;
    private CommittedSnapshotLoader mockLoader;
    private SchemaTruthMetrics metrics;
    private Clock clock;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        registry = new SimpleMeterRegistry();
        mockLoader = mock(CommittedSnapshotLoader.class);
        ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registryProvider =
                (ObjectProvider<io.micrometer.core.instrument.MeterRegistry>) mock(ObjectProvider.class);
        when(registryProvider.getIfAvailable()).thenReturn(registry);
        clock = Clock.fixed(Instant.parse("2026-05-07T12:00:00Z"), ZoneId.of("UTC"));

        metrics = new SchemaTruthMetrics(registryProvider, mockLoader, clock);
        metrics.registerGauges();
    }

    @Test
    void recordLookup_incrementsCounterWithSchemaModeTag() {
        SchemaTruthLookupContext ctx = new SchemaTruthLookupContext(
                "fin-muhasebe-detay", "yearly",
                SchemaTruthLookupPolicy.RUNTIME_DEGRADED_TYPE, "filter_translator");

        metrics.recordLookup(ctx);
        metrics.recordLookup(ctx);

        Counter counter = registry.find(SchemaTruthMetrics.LOOKUP_TOTAL)
                .tag("schema_mode", "yearly").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(2.0);
    }

    @Test
    void recordFallback_incrementsCounterWithTierAndSchemaModeTag() {
        SchemaTruthLookupContext ctx = new SchemaTruthLookupContext(
                "fin-muhasebe-detay", "current",
                SchemaTruthLookupPolicy.RUNTIME_DEGRADED_TYPE, "sql_builder");

        metrics.recordFallback(ctx, "committed_snapshot");

        Counter counter = registry.find(SchemaTruthMetrics.FALLBACK_TOTAL)
                .tag("tier", "committed_snapshot")
                .tag("schema_mode", "current").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void recordCacheHit_incrementsCounterWithSchemaModeTag() {
        SchemaTruthLookupContext ctx = new SchemaTruthLookupContext(
                "fin-muhasebe-detay", "yearly",
                SchemaTruthLookupPolicy.RUNTIME_DEGRADED_TYPE, "filter_translator");

        metrics.recordCacheHit(ctx);

        Counter counter = registry.find(SchemaTruthMetrics.CACHE_HIT_TOTAL)
                .tag("schema_mode", "yearly").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void schemaModeTag_unknownWhenContextEmpty() {
        SchemaTruthLookupContext ctx = new SchemaTruthLookupContext(
                "test", null,
                SchemaTruthLookupPolicy.BUILD_DETERMINISTIC, "test_consumer");

        metrics.recordLookup(ctx);

        Counter counter = registry.find(SchemaTruthMetrics.LOOKUP_TOTAL)
                .tag("schema_mode", "unknown").counter();
        assertThat(counter).isNotNull();
    }

    @Test
    void refreshSnapshotAgeGauge_setsAgeAndWarnFlag() {
        // Snapshot mtime 35 days ago → age=35, warn=1 (>30 threshold)
        when(mockLoader.snapshotAgeDays(clock)).thenReturn(Optional.of(35L));

        metrics.refreshSnapshotAgeGauge();

        assertThat(metrics.getSnapshotAgeDaysGauge()).isEqualTo(35L);
        assertThat(metrics.getSnapshotAgeWarnGauge()).isEqualTo(1L);
    }

    @Test
    void refreshSnapshotAgeGauge_noWarnWhenAgeUnderThreshold() {
        when(mockLoader.snapshotAgeDays(clock)).thenReturn(Optional.of(15L));

        metrics.refreshSnapshotAgeGauge();

        assertThat(metrics.getSnapshotAgeDaysGauge()).isEqualTo(15L);
        assertThat(metrics.getSnapshotAgeWarnGauge()).isEqualTo(0L);
    }

    @Test
    void refreshCacheMissBurstGauge_oneWhenMissRateAboveFifty() {
        // Simulate 100 lookups + 30 cache hits → 70% miss rate → burst=1
        SchemaTruthLookupContext ctx = new SchemaTruthLookupContext(
                "test", "yearly",
                SchemaTruthLookupPolicy.RUNTIME_DEGRADED_TYPE, "test_consumer");
        for (int i = 0; i < 100; i++) {
            metrics.recordLookup(ctx);
        }
        for (int i = 0; i < 30; i++) {
            metrics.recordCacheHit(ctx);
        }

        metrics.refreshCacheMissBurstGauge();

        assertThat(metrics.getCacheMissBurstGauge()).isEqualTo(1L);
    }

    @Test
    void refreshCacheMissBurstGauge_zeroWhenInsufficientSamples() {
        // Less than 10 lookups → burst=0
        SchemaTruthLookupContext ctx = new SchemaTruthLookupContext(
                "test", "yearly",
                SchemaTruthLookupPolicy.RUNTIME_DEGRADED_TYPE, "test_consumer");
        for (int i = 0; i < 5; i++) {
            metrics.recordLookup(ctx);
        }

        metrics.refreshCacheMissBurstGauge();

        assertThat(metrics.getCacheMissBurstGauge()).isEqualTo(0L);
    }
}

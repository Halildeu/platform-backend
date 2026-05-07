package com.example.report.schema.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Phase 2 Program 8c — RequestColumnTypeCache unit tests.
 *
 * <p>Direct instantiation (no Spring context); @RequestScope lifecycle
 * test'i Phase-2-Program-8d Spring MVC slice IT'sinde doğrulanır.
 */
class RequestColumnTypeCacheTest {

    private RequestColumnTypeCache cache;

    @BeforeEach
    void setUp() {
        cache = new RequestColumnTypeCache();
    }

    @Test
    void getColumnType_returnsEmptyWhenNotCached() {
        Optional<String> result = cache.getColumnType("fin-muhasebe-detay", "AMOUNT");

        assertThat(result).isEmpty();
        assertThat(cache.size()).isZero();
    }

    @Test
    void putColumnType_cachesByReportKeyAndFieldName() {
        cache.putColumnType("fin-muhasebe-detay", "AMOUNT", "DECIMAL(18,2)");

        Optional<String> result = cache.getColumnType("fin-muhasebe-detay", "AMOUNT");

        assertThat(result).contains("DECIMAL(18,2)");
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    void putColumnType_separateReportKeysIsolated() {
        cache.putColumnType("fin-muhasebe-detay", "AMOUNT", "DECIMAL(18,2)");
        cache.putColumnType("stok-hareket", "AMOUNT", "INT");

        assertThat(cache.getColumnType("fin-muhasebe-detay", "AMOUNT")).contains("DECIMAL(18,2)");
        assertThat(cache.getColumnType("stok-hareket", "AMOUNT")).contains("INT");
        assertThat(cache.size()).isEqualTo(2);
    }

    @Test
    void putColumnType_nullsAreIgnored() {
        cache.putColumnType(null, "AMOUNT", "DECIMAL");
        cache.putColumnType("fin-muhasebe-detay", null, "DECIMAL");
        cache.putColumnType("fin-muhasebe-detay", "AMOUNT", null);

        assertThat(cache.size()).isZero();
    }
}

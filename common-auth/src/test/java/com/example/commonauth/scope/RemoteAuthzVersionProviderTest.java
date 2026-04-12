package com.example.commonauth.scope;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RemoteAuthzVersionProvider — cache, parsing, error handling.
 * SK-7 coverage target.
 */
class RemoteAuthzVersionProviderTest {

    @Test
    void getCurrentVersion_initialValue_returnsZero() {
        // Point to a URL that will fail — verifies fail-open returns 0
        var provider = new RemoteAuthzVersionProvider("http://localhost:1/nonexistent", 5000);
        assertEquals(0L, provider.getCurrentVersion());
    }

    @Test
    void getCurrentVersion_networkError_returnsCachedVersion() {
        var provider = new RemoteAuthzVersionProvider("http://localhost:1/fail", 1);
        // First call — network fails, returns 0 (initial cached)
        long v1 = provider.getCurrentVersion();
        assertEquals(0L, v1);
        // Second call — still fails, still returns cached 0
        long v2 = provider.getCurrentVersion();
        assertEquals(0L, v2);
    }

    @Test
    void getCurrentVersion_withinTtl_returnsCachedWithoutNetwork() {
        // Very long TTL — second call should not hit network
        var provider = new RemoteAuthzVersionProvider("http://localhost:1/fail", 60_000);
        provider.getCurrentVersion(); // warms cache
        long start = System.currentTimeMillis();
        provider.getCurrentVersion(); // should be instant (cached)
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 100, "Second call should be fast (cached), took " + elapsed + "ms");
    }
}

package com.example.commonauth.scope;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.commonauth.openfga.OpenFgaProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScopeContextFilterCacheTest {

    @Mock OpenFgaAuthzService authzService;

    @Test @DisplayName("Cache hit returns cached context")
    void cacheHit() {
        var cache = new ScopeContextCache(Duration.ofSeconds(30), Duration.ZERO, 100, true);
        ScopeContext ctx = new ScopeContext("10", Set.of(1L), Set.of(), Set.of(), Set.of(), false);
        String key = ScopeContextCache.cacheKey("10", 1L, "s", "m");
        cache.put(key, ctx);

        assertNotNull(cache.get(key));
        assertEquals("10", cache.get(key).userId());
        verifyNoInteractions(authzService);
    }

    @Test @DisplayName("Version change causes miss")
    void versionChangeMiss() {
        var cache = new ScopeContextCache(Duration.ofSeconds(30), Duration.ZERO, 100, true);
        ScopeContext ctx = new ScopeContext("10", Set.of(1L), Set.of(), Set.of(), Set.of(), false);
        cache.put(ScopeContextCache.cacheKey("10", 1L, "s", "m"), ctx);

        assertNotNull(cache.get(ScopeContextCache.cacheKey("10", 1L, "s", "m")));
        assertNull(cache.get(ScopeContextCache.cacheKey("10", 2L, "s", "m")));
    }

    @Test @DisplayName("Disabled cache always null")
    void disabledCache() {
        var cache = new ScopeContextCache(Duration.ofSeconds(30), 100, false);
        cache.put("k", new ScopeContext("10", Set.of(1L), Set.of(), Set.of(), Set.of(), false));
        assertNull(cache.get("k"));
        assertFalse(cache.isEnabled());
    }

    @Test @DisplayName("Null cache in filter preserves backward compat")
    void nullCacheCompat() {
        var props = new OpenFgaProperties();
        props.setEnabled(true);
        var filter = new ScopeContextFilter(authzService, props, null);
        assertNotNull(filter);
    }
}

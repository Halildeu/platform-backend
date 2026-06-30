package com.example.commonauth.openfga;

import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.client.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * checkNoCache / batchCheckNoCache (Faz 26 gp-core, Codex 019f1913) MUST bypass
 * the positive check cache so gp-core's version-aware decision cache is never
 * shadowed by a version-unaware stale allow. Contrasted against the cached
 * {@code check()} which serves a repeat from cache.
 */
@ExtendWith(MockitoExtension.class)
class OpenFgaAuthzServiceNoCacheTest {

    @Mock
    OpenFgaClient client;

    private OpenFgaAuthzService service;

    @BeforeEach
    void setUp() {
        var props = new OpenFgaProperties();
        props.setEnabled(true);
        props.setStoreId("gp-store");
        props.setModelId("gp-model");
        service = new OpenFgaAuthzService(client, props);
    }

    @Test
    void checkNoCache_invokesClientEveryTime_noPositiveCache() throws Exception {
        var resp = mock(ClientCheckResponse.class);
        when(resp.getAllowed()).thenReturn(true);
        when(client.check(any(ClientCheckRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(resp));

        assertTrue(service.checkNoCache("u1", "viewer", "control_instance", "1"));
        assertTrue(service.checkNoCache("u1", "viewer", "control_instance", "1"));

        // Two calls → client hit TWICE: no stale positive can be served from a cache.
        verify(client, times(2)).check(any(ClientCheckRequest.class));
    }

    @Test
    void cachedCheck_servesRepeatFromCache_invokesClientOnce() throws Exception {
        var resp = mock(ClientCheckResponse.class);
        when(resp.getAllowed()).thenReturn(true);
        when(client.check(any(ClientCheckRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(resp));

        assertTrue(service.check("u2", "viewer", "control_instance", "1"));
        assertTrue(service.check("u2", "viewer", "control_instance", "1"));

        // Contrast: the cached path serves the second from cache (version-unaware) — exactly
        // why gp-core uses checkNoCache instead.
        verify(client, times(1)).check(any(ClientCheckRequest.class));
    }

    @Test
    void checkNoCache_failsClosedOnException() throws Exception {
        when(client.check(any(ClientCheckRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("connection refused")));
        assertFalse(service.checkNoCache("u1", "viewer", "control_instance", "1"));
    }

    @Test
    void batchCheckNoCache_fallback_usesNoCachePerItem() throws Exception {
        // Native batch fails → fallback path → per-item checkNoCache (NOT cached checkWithReason).
        when(client.batchCheck(any(ClientBatchCheckRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("batch down")));
        var resp = mock(ClientCheckResponse.class);
        when(resp.getAllowed()).thenReturn(true);
        when(client.check(any(ClientCheckRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(resp));

        var req = List.of(new OpenFgaAuthzService.BatchCheckRequest("viewer", "control_instance", "1"));
        assertTrue(service.batchCheckNoCache("u3", req).get(0).allowed());
        assertTrue(service.batchCheckNoCache("u3", req).get(0).allowed());

        // If the fallback used the cached path, the 2nd call would serve from cache (times(1)).
        // times(2) proves the fallback is genuinely cache-less per item.
        verify(client, times(2)).check(any(ClientCheckRequest.class));
    }

    @Test
    void batchCheckNoCache_nativeResult_aligned() throws Exception {
        var single = mock(ClientBatchCheckSingleResponse.class);
        when(single.isAllowed()).thenReturn(true);
        var batchResp = mock(ClientBatchCheckResponse.class);
        when(batchResp.getResult()).thenReturn(List.of(single));
        when(client.batchCheck(any(ClientBatchCheckRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(batchResp));

        var req = List.of(new OpenFgaAuthzService.BatchCheckRequest("viewer", "control_instance", "1"));
        var out = service.batchCheckNoCache("u4", req);
        assertEquals(1, out.size());
        assertTrue(out.get(0).allowed());
    }
}

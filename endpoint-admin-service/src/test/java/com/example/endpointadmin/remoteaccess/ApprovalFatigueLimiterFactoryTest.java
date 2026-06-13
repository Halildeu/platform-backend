package com.example.endpointadmin.remoteaccess;

import com.example.endpointadmin.remoteaccess.ApprovalFatigueLimiterFactory.LimiterType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Faz 22.6 D10 approval-chain (Codex 019ebe06) — the fatigue-limiter factory matrix: the process-local IN_MEMORY
 * limiter builds outside prod but is forbidden in a production-like profile (a process-local cap is bypassable
 * across restart/replicas); the durable/distributed limiter is not-yet-implemented.
 */
class ApprovalFatigueLimiterFactoryTest {

    @Test
    void inMemoryBuildsOutsideProd() {
        assertNotNull(ApprovalFatigueLimiterFactory.create(LimiterType.IN_MEMORY, 5, 60_000L, false));
    }

    @Test
    void inMemoryIsForbiddenInAProdLikeProfile() {
        assertThrows(IllegalStateException.class,
                () -> ApprovalFatigueLimiterFactory.create(LimiterType.IN_MEMORY, 5, 60_000L, true));
    }

    @Test
    void durableDistributedIsNotYetImplemented() {
        assertThrows(IllegalStateException.class,
                () -> ApprovalFatigueLimiterFactory.create(LimiterType.DURABLE_DISTRIBUTED, 5, 60_000L, false));
    }

    @Test
    void aNullTypeDefaultsToInMemory() {
        assertNotNull(ApprovalFatigueLimiterFactory.create(null, 5, 60_000L, false));
        assertThrows(IllegalStateException.class,
                () -> ApprovalFatigueLimiterFactory.create(null, 5, 60_000L, true)); // IN_MEMORY → prod-forbidden
    }

    @Test
    void theLimiterStillEnforcesItsCap() {
        // a smoke that the built limiter behaves (cap 1 → second approval in the window is FATIGUED)
        ApprovalFatigueLimiter limiter = ApprovalFatigueLimiterFactory.create(LimiterType.IN_MEMORY, 1, 60_000L, false);
        assertEquals(ApprovalFatigueLimiter.Decision.ALLOWED, limiter.recordApproval("u1", 1_000L));
        assertEquals(ApprovalFatigueLimiter.Decision.FATIGUED, limiter.recordApproval("u1", 2_000L));
    }

    @Test
    void badBoundsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ApprovalFatigueLimiterFactory.create(LimiterType.IN_MEMORY, 0, 60_000L, false));
    }
}

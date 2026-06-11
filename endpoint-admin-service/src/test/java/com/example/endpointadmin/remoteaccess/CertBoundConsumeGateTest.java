package com.example.endpointadmin.remoteaccess;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 B1.1c — connect-time consume gate (Codex 019eb54b check-list #2 + the no-burn deny).
 */
class CertBoundConsumeGateTest {

    private static final Instant T0 = Instant.parse("2026-06-11T10:00:00Z");
    private static final Instant EXP = T0.plus(Duration.ofHours(1));
    private static final String TP_A = "ab".repeat(32);

    private final InMemoryTokenLifecycleStore store = new InMemoryTokenLifecycleStore();
    private final AtomicInteger legacyMeter = new AtomicInteger();

    private CertBoundConsumeGate gate(CertBindingGuard.Policy policy) {
        return new CertBoundConsumeGate(store, policy, legacyMeter::incrementAndGet);
    }

    @Test
    void presentedThumbprintIsPinnedAtomicallyWithTheConsume() {
        var r = gate(CertBindingGuard.Policy.REQUIRE_BOUND).consume("jti-1", EXP, T0, TP_A);
        assertTrue(r.accepted());
        assertEquals(CertBindingGuard.Decision.BOUND_MATCH, r.certDecision());
        assertEquals(TokenLifecycleStore.ConsumeOutcome.ACCEPTED, r.storeOutcome());
        // the binding landed in the SAME single-use record (B1.1a pin-at-consume)
        assertEquals(TP_A, store.boundThumbprint("jti-1").orElseThrow());
        assertEquals(0, legacyMeter.get()); // a bound consume is NOT a legacy issuance
    }

    @Test
    void certlessConnectUnderRequireBoundIsDeniedWithoutBurningTheToken() {
        var gate = gate(CertBindingGuard.Policy.REQUIRE_BOUND);
        var denied = gate.consume("jti-2", EXP, T0, null);
        assertFalse(denied.accepted());
        assertEquals(CertBindingGuard.Decision.UNBOUND_REJECTED, denied.certDecision());
        assertNull(denied.storeOutcome()); // the store was never consulted — no burn, no legacy row
        assertEquals(0, legacyMeter.get());
        // the legitimate holder can still claim the very same jti with its cert (single-use intact):
        var legit = gate.consume("jti-2", EXP, T0.plusSeconds(1), TP_A);
        assertTrue(legit.accepted());
        assertEquals(TP_A, store.boundThumbprint("jti-2").orElseThrow());
    }

    @Test
    void blankPresentedThumbprintIsTreatedAsMissing() {
        var denied = gate(CertBindingGuard.Policy.REQUIRE_BOUND).consume("jti-3", EXP, T0, "  ");
        assertFalse(denied.accepted());
        assertNull(denied.storeOutcome());
    }

    @Test
    void legacyUnboundConsumeUnderAllowIsAcceptedAndMeteredOnce() {
        // check-list #2: LEGACY_UNBOUND_ISSUANCE increments at consume-with-null — only via the explicit flag
        var gate = gate(CertBindingGuard.Policy.ALLOW_LEGACY_UNBOUND);
        var r = gate.consume("jti-4", EXP, T0, null);
        assertTrue(r.accepted());
        assertEquals(CertBindingGuard.Decision.UNBOUND_ALLOWED, r.certDecision());
        assertEquals(TokenLifecycleStore.ConsumeOutcome.ACCEPTED, r.storeOutcome());
        assertTrue(store.boundThumbprint("jti-4").isEmpty()); // recorded legacy-unbound
        assertEquals(1, legacyMeter.get());
    }

    @Test
    void deniedLegacyConsumeDoesNotIncrementTheIssuanceMeter() {
        var gate = gate(CertBindingGuard.Policy.ALLOW_LEGACY_UNBOUND);
        assertTrue(gate.consume("jti-5", EXP, T0, null).accepted());
        assertEquals(1, legacyMeter.get());
        // replay of the same jti → ALREADY_USED, not an issuance → meter unchanged
        var replay = gate.consume("jti-5", EXP, T0.plusSeconds(1), null);
        assertFalse(replay.accepted());
        assertEquals(TokenLifecycleStore.ConsumeOutcome.ALREADY_USED, replay.storeOutcome());
        assertEquals(1, legacyMeter.get());
        // an already-expired legacy consume is also not an issuance
        var expired = gate.consume("jti-6", T0.minusSeconds(1), T0, null);
        assertFalse(expired.accepted());
        assertEquals(1, legacyMeter.get());
    }

    @Test
    void storeOutcomePassesThroughForBoundConsumes() {
        var gate = gate(CertBindingGuard.Policy.REQUIRE_BOUND);
        assertTrue(gate.consume("jti-7", EXP, T0, TP_A).accepted());
        var replay = gate.consume("jti-7", EXP, T0.plusSeconds(1), TP_A);
        assertFalse(replay.accepted()); // single-use: a replay even under the SAME cert is denied
        assertEquals(TokenLifecycleStore.ConsumeOutcome.ALREADY_USED, replay.storeOutcome());
        store.setAvailable(false);
        var down = gate.consume("jti-8", EXP, T0, TP_A);
        assertFalse(down.accepted()); // fail-closed on partition
        assertEquals(TokenLifecycleStore.ConsumeOutcome.STORE_UNAVAILABLE, down.storeOutcome());
    }

    @Test
    void nullPolicyCoercesToFailClosedRequireBound() {
        var denied = new CertBoundConsumeGate(store, null, legacyMeter::incrementAndGet)
                .consume("jti-9", EXP, T0, null);
        assertFalse(denied.accepted());
        assertNull(denied.storeOutcome());
        assertEquals(0, legacyMeter.get());
    }
}

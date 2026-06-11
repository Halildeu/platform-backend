package com.example.endpointadmin.remoteaccess;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Faz 22.6 B1.1 — the in-memory {@link TokenLifecycleStore} cert-binding contract: {@code consume} pins
 * the thumbprint atomically with the single-use USED transition, {@code boundThumbprint} reads it, the
 * legacy 3-arg consume + a blank thumbprint record unbound, and revoke preserves the binding for audit.
 * (The DB-CAS equivalent is asserted against real Postgres in the integration test.)
 */
class CertBindingStoreTest {

    private static final Instant T = Instant.parse("2026-06-11T12:00:00Z");
    private static final Instant EXP = T.plus(Duration.ofHours(1));

    @Test
    void consumePinsThumbprintAtomically() {
        InMemoryTokenLifecycleStore s = new InMemoryTokenLifecycleStore();
        assertEquals(TokenLifecycleStore.ConsumeOutcome.ACCEPTED, s.consume("jti-1", EXP, T, "thumb-abc"));
        assertEquals(Optional.of("thumb-abc"), s.boundThumbprint("jti-1"));
    }

    @Test
    void legacyThreeArgConsumeRecordsUnbound() {
        InMemoryTokenLifecycleStore s = new InMemoryTokenLifecycleStore();
        assertEquals(TokenLifecycleStore.ConsumeOutcome.ACCEPTED, s.consume("jti-2", EXP, T)); // default → null
        assertEquals(Optional.empty(), s.boundThumbprint("jti-2"));
    }

    @Test
    void blankThumbprintNormalizesToUnbound() {
        InMemoryTokenLifecycleStore s = new InMemoryTokenLifecycleStore();
        s.consume("jti-3", EXP, T, "   ");
        assertEquals(Optional.empty(), s.boundThumbprint("jti-3"));
    }

    @Test
    void revokePreservesTheBindingForAudit() {
        InMemoryTokenLifecycleStore s = new InMemoryTokenLifecycleStore();
        s.consume("jti-4", EXP, T, "thumb-xyz");
        assertEquals(TokenLifecycleStore.MutationOutcome.UPDATED, s.revoke("jti-4"));
        assertEquals(Optional.of("thumb-xyz"), s.boundThumbprint("jti-4"));
    }

    @Test
    void unknownJtiHasNoBinding() {
        assertEquals(Optional.empty(), new InMemoryTokenLifecycleStore().boundThumbprint("nope"));
    }

    // ---- B1.1c: status() — the atomic liveness+binding enforcement read ----

    @Test
    void statusReturnsLivenessAndBindingFromOneAtomicRead() {
        InMemoryTokenLifecycleStore s = new InMemoryTokenLifecycleStore();
        s.consume("jti-5", EXP, T, "thumb-abc");
        TokenLifecycleStore.TokenStatus bound = s.status("jti-5", T.plusSeconds(1));
        assertEquals(TokenLifecycleStore.TokenLiveCheckResult.LIVE, bound.liveness());
        assertEquals("thumb-abc", bound.boundThumbprint());

        s.consume("jti-6", EXP, T); // legacy-unbound
        TokenLifecycleStore.TokenStatus unbound = s.status("jti-6", T.plusSeconds(1));
        assertEquals(TokenLifecycleStore.TokenLiveCheckResult.LIVE, unbound.liveness());
        // LIVE ⇒ the row was read ⇒ null binding authoritatively means legacy-unbound
        assertEquals(null, unbound.boundThumbprint());
    }

    @Test
    void statusFailsClosedOnPartitionUnknownAndRevoked() {
        InMemoryTokenLifecycleStore s = new InMemoryTokenLifecycleStore();
        assertEquals(TokenLifecycleStore.TokenLiveCheckResult.NOT_FOUND, s.status("nope", T).liveness());

        s.consume("jti-7", EXP, T, "thumb-xyz");
        s.revoke("jti-7");
        TokenLifecycleStore.TokenStatus revoked = s.status("jti-7", T.plusSeconds(1));
        assertEquals(TokenLifecycleStore.TokenLiveCheckResult.REVOKED, revoked.liveness());
        assertEquals("thumb-xyz", revoked.boundThumbprint()); // binding preserved for audit

        s.setAvailable(false);
        TokenLifecycleStore.TokenStatus down = s.status("jti-7", T.plusSeconds(2));
        assertEquals(TokenLifecycleStore.TokenLiveCheckResult.STORE_UNAVAILABLE, down.liveness());
        assertEquals(null, down.boundThumbprint()); // never a stale binding under a partition
    }
}

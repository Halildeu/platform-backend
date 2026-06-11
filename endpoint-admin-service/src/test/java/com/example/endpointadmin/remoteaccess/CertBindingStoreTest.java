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
}

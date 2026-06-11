package com.example.endpointadmin.remoteaccess;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 B2 — TokenLifecycleStore contract tests (Codex 019eb54b criteria #1/#4/#7/#8 + REVISE absorb).
 */
class TokenLifecycleStoreTest {

    private static final Instant T0 = Instant.parse("2026-06-11T10:00:00Z");
    private static final Instant EXP = T0.plus(Duration.ofHours(1));

    private final TokenLifecycleStore store = new InMemoryTokenLifecycleStore();

    @Test
    void firstConsumeAcceptedThenReplayDenied() {
        assertEquals(TokenLifecycleStore.ConsumeOutcome.ACCEPTED, store.consume("jti-1", EXP, T0));
        assertTrue(store.isTokenLive("jti-1", T0).isLive());
        assertEquals(TokenLifecycleStore.ConsumeOutcome.ALREADY_USED, store.consume("jti-1", EXP, T0));
    }

    @Test
    void blankOrNullArgsAreInvalid() {
        assertEquals(TokenLifecycleStore.ConsumeOutcome.INVALID, store.consume("  ", EXP, T0));
        assertEquals(TokenLifecycleStore.ConsumeOutcome.INVALID, store.consume(null, EXP, T0));
        assertEquals(TokenLifecycleStore.ConsumeOutcome.INVALID, store.consume("jti", null, T0));
        assertEquals(TokenLifecycleStore.ConsumeOutcome.INVALID, store.consume("jti", EXP, null));
    }

    @Test
    void alreadyExpiredTokenIsRejectedOnConsume() {
        // now >= expiresAt at first sight → EXPIRED, never ACCEPTED.
        assertEquals(TokenLifecycleStore.ConsumeOutcome.EXPIRED, store.consume("jti-old", T0, T0.plusSeconds(1)));
    }

    @Test
    void livenessIsTimeAwareWithoutAnExplicitExpireCall() {
        // Codex absorb: a token past its expiresAt is not live even if expire() was never called.
        assertEquals(TokenLifecycleStore.ConsumeOutcome.ACCEPTED, store.consume("jti-t", EXP, T0));
        assertEquals(TokenLifecycleStore.TokenLiveCheckResult.LIVE, store.isTokenLive("jti-t", EXP.minusSeconds(1)));
        assertEquals(TokenLifecycleStore.TokenLiveCheckResult.EXPIRED, store.isTokenLive("jti-t", EXP)); // at expiry → not live
        assertEquals(TokenLifecycleStore.TokenLiveCheckResult.EXPIRED, store.isTokenLive("jti-t", EXP.plusSeconds(1)));
    }

    @Test
    void revokeIsAuthoritativeAndKillsLiveness() {
        assertEquals(TokenLifecycleStore.ConsumeOutcome.ACCEPTED, store.consume("jti-r", EXP, T0));
        assertTrue(store.isTokenLive("jti-r", T0).isLive());
        assertEquals(TokenLifecycleStore.MutationOutcome.UPDATED, store.revoke("jti-r"));
        assertEquals(TokenLifecycleStore.TokenLiveCheckResult.REVOKED, store.isTokenLive("jti-r", T0));
        assertEquals(TokenLifecycleStore.ConsumeOutcome.REVOKED, store.consume("jti-r", EXP, T0));
        // idempotent: second revoke is a NOOP
        assertEquals(TokenLifecycleStore.MutationOutcome.NOOP, store.revoke("jti-r"));
    }

    @Test
    void revokeWinsOverExpire() {
        // expire first, then revoke → REVOKED (revocation authoritative for forensics).
        assertEquals(TokenLifecycleStore.MutationOutcome.UPDATED, store.expire("jti-x"));
        assertEquals(TokenLifecycleStore.MutationOutcome.UPDATED, store.revoke("jti-x"));
        assertEquals(TokenLifecycleStore.ConsumeOutcome.REVOKED, store.consume("jti-x", EXP, T0));
        // and expire must NOT override a revoked jti
        assertEquals(TokenLifecycleStore.MutationOutcome.NOOP, store.expire("jti-x"));
    }

    @Test
    void storeUnavailableFailsClosed() {
        InMemoryTokenLifecycleStore s = new InMemoryTokenLifecycleStore();
        assertEquals(TokenLifecycleStore.ConsumeOutcome.ACCEPTED, s.consume("jti-a", EXP, T0));
        s.setAvailable(false); // simulate partition (criterion #7)
        assertEquals(TokenLifecycleStore.ConsumeOutcome.STORE_UNAVAILABLE, s.consume("jti-b", EXP, T0));
        assertEquals(TokenLifecycleStore.TokenLiveCheckResult.STORE_UNAVAILABLE, s.isTokenLive("jti-a", T0)); // fail-closed
    }

    @Test
    void revokeAndExpireSurfaceStoreUnavailableUnderPartition() {
        // Codex absorb: a failed mutation must NOT masquerade as NOOP (the fanout must retry/alert).
        InMemoryTokenLifecycleStore s = new InMemoryTokenLifecycleStore();
        s.consume("jti-p", EXP, T0);
        s.setAvailable(false);
        assertEquals(TokenLifecycleStore.MutationOutcome.STORE_UNAVAILABLE, s.revoke("jti-p"));
        assertEquals(TokenLifecycleStore.MutationOutcome.STORE_UNAVAILABLE, s.expire("jti-p"));
    }

    @Test
    void concurrentConsumeOfSameJtiAcceptsExactlyOnce() throws InterruptedException {
        int threads = 128;
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger accepted = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (store.consume("jti-race", EXP, T0).isAccepted()) {
                        accepted.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "threads did not finish");
        pool.shutdownNow();
        assertEquals(1, accepted.get(), "single-use violated: " + accepted.get() + " accepted");
    }

    @Test
    void hardKillWiringRevokedTokenDrivesReevaluateActiveToTokenRevoked() {
        store.consume("jti-live", EXP, T0);
        RemoteSessionStateMachine sm = new RemoteSessionStateMachine();
        RemoteSessionPreconditions healthy = new RemoteSessionPreconditions(
                true, true, true, store.isTokenLive("jti-live", T0).isLive(), true, true);
        assertEquals(RemoteSessionState.ACTIVE, sm.reevaluateActive(RemoteSessionState.ACTIVE, healthy).target());
        store.revoke("jti-live");
        RemoteSessionPreconditions afterRevoke = new RemoteSessionPreconditions(
                true, true, true, store.isTokenLive("jti-live", T0).isLive(), true, true);
        RemoteSessionStateMachine.Reevaluation r = sm.reevaluateActive(RemoteSessionState.ACTIVE, afterRevoke);
        assertEquals(RemoteSessionState.ABORTED, r.target());
        assertEquals(RemoteSessionStateMachine.KillReason.TOKEN_REVOKED, r.reason());
        assertTrue(r.isKill());
    }
}

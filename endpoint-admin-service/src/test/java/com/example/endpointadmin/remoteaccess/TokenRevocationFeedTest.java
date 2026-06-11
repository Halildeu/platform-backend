package com.example.endpointadmin.remoteaccess;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 B2 — revocation channel tests (Codex 019eb54b lock-down): the feed fans a revocation out so a
 * subscribed heartbeat revokes in the store + the session is killed; carries the t0/correlation metadata.
 */
class TokenRevocationFeedTest {

    private static final Instant T0 = Instant.parse("2026-06-11T10:00:00Z");
    private static final Instant EXP = T0.plus(Duration.ofHours(1));

    @Test
    void publishDrivesSubscribedHeartbeatToRevokeAndKill() {
        TokenLifecycleStore store = new InMemoryTokenLifecycleStore();
        store.consume("jti-1", EXP, T0);
        TokenRevocationFeed feed = new InMemoryTokenRevocationFeed();
        RemoteSessionStateMachine sm = new RemoteSessionStateMachine();

        // the heartbeat subscriber: on a revocation event, mark the store + kill the live session.
        AtomicReference<RemoteSessionStateMachine.Reevaluation> killed = new AtomicReference<>();
        feed.subscribe(ev -> {
            store.revoke(ev.jti());
            RemoteSessionPreconditions now = new RemoteSessionPreconditions(
                    true, true, true, store.isTokenLive(ev.jti(), T0).isLive(), true, true, true);
            killed.set(sm.reevaluateActive(RemoteSessionState.ACTIVE, now));
        });

        // before revocation the token is live
        assertTrue(store.isTokenLive("jti-1", T0).isLive());

        feed.publish(new TokenRevocationFeed.RevocationEvent(
                "jti-1", T0, "OPERATOR_ABORT", "req-42", "sess-1", "admin-2",
                TokenRevocationFeed.Severity.SECURITY_INCIDENT));

        assertEquals(RemoteSessionState.ABORTED, killed.get().target());
        assertEquals(RemoteSessionStateMachine.KillReason.TOKEN_REVOKED, killed.get().reason());
        assertEquals(TokenLifecycleStore.TokenLiveCheckResult.REVOKED, store.isTokenLive("jti-1", T0));
    }

    @Test
    void fanoutReachesAllSubscribersAndSurfacesAFailureWithoutSilentDrop() {
        TokenRevocationFeed feed = new InMemoryTokenRevocationFeed();
        AtomicInteger reached = new AtomicInteger();
        feed.subscribe(ev -> reached.incrementAndGet());
        feed.subscribe(ev -> {
            throw new IllegalStateException("subscriber blew up");
        });
        feed.subscribe(ev -> reached.incrementAndGet());

        TokenRevocationFeed.RevocationEvent ev = new TokenRevocationFeed.RevocationEvent(
                "jti-x", T0, "POLICY_CHANGE", "req-7", null, "system",
                TokenRevocationFeed.Severity.ROUTINE);

        // a throwing subscriber must not silently swallow the event (no fail-open) — it surfaces…
        assertThrows(IllegalStateException.class, () -> feed.publish(ev));
        // …but the other two subscribers still received it (fanout reliability)
        assertEquals(2, reached.get());
    }

    @Test
    void eventCarriesT0AndCorrelationMetadataForSlo() {
        TokenRevocationFeed.RevocationEvent ev = new TokenRevocationFeed.RevocationEvent(
                "jti-7", T0, "OPERATOR_ABORT", "req-99", "sess-9", "admin-1",
                TokenRevocationFeed.Severity.URGENT);
        assertEquals(T0, ev.revokedAt()); // t0 marker for revocation_latency_ms
        assertEquals("req-99", ev.requestId());
        assertEquals("sess-9", ev.correlatedSessionId());
        assertEquals(TokenRevocationFeed.Severity.URGENT, ev.severity());
    }
}

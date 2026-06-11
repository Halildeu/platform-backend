package com.example.endpointadmin.remoteaccess;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 B2.2c — the SOURCE-BOUND hard-kill SLO harness (Codex 019eb54b criterion #6, Q2) against a
 * real PostgreSQL (Testcontainers + Flyway V63 + the prod {@link DbCasTokenLifecycleStore}). It models the
 * real ordering — the SOURCE revokes a token (establishing the DB {@code revoked_at} = t0), then the
 * reconciler reacts at {@code now > t0} (its own defensive revoke is a NOOP that preserves t0) — and
 * asserts that the kill latency is anchored EXACTLY on the DB {@code revoked_at} (not the event clock) and
 * stays within the SLO budget (P95 ≤ 5s / P99 ≤ 10s / max ≤ 30s).
 *
 * <p><b>Scope (honest):</b> this proves the latency MEASUREMENT is correct + source-bound + the SLO
 * aggregation harness works, with real DB timestamps. The in-harness latencies are sub-second because
 * there is no real tunnel/network — the real-world fanout latency under a live tunnel is exercised only by
 * the owner-gated attended pilot (ADR-0034 §11/D10, B2.3 e2e). This is the agent-completable evidence.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RevocationSloPostgresIntegrationTest {

    private static final String SCHEMA = "endpoint_admin_service";

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("endpoint_admin")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @Autowired
    private JdbcTemplate jdbc;

    private RemoteSessionRevocationReconciler reconciler() {
        DbCasTokenLifecycleStore store = new DbCasTokenLifecycleStore(jdbc, SCHEMA);
        return new RemoteSessionRevocationReconciler(
                store, new RemoteSessionHeartbeat(store, new RemoteSessionStateMachine(), Duration.ofSeconds(30)));
    }

    private static TokenRevocationFeed.RevocationEvent event(String jti, Instant t0) {
        return new TokenRevocationFeed.RevocationEvent(
                jti, t0, "OPERATOR_ABORT", "req", "sess", "operator:1", TokenRevocationFeed.Severity.URGENT);
    }

    @Test
    void sourceBoundPushLatencyMeetsSlo() {
        DbCasTokenLifecycleStore store = new DbCasTokenLifecycleStore(jdbc, SCHEMA);
        RemoteSessionRevocationReconciler reconciler = new RemoteSessionRevocationReconciler(
                store, new RemoteSessionHeartbeat(store, new RemoteSessionStateMachine(), Duration.ofSeconds(30)));

        int n = 100;
        List<Long> latencies = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String jti = "slo-" + i;
            Instant t = Instant.now();
            store.consume(jti, t.plus(Duration.ofHours(1)), t);  // USED
            store.revoke(jti);                                   // SOURCE revoke ⇒ DB revoked_at = t0
            Instant dbT0 = store.revokedAt(jti).orElseThrow();

            InMemorySessionRegistry reg = new InMemorySessionRegistry();
            reg.put(new RemoteSessionHeartbeat.SessionSnapshot(
                    "s" + i, jti, RemoteSessionState.ACTIVE, 0L, Instant.now()));

            Instant now = Instant.now();                         // decision happens after t0 (now ≥ dbT0)
            List<RemoteSessionRevocationReconciler.ReconcileOutcome> out =
                    reconciler.onRevocation(event(jti, dbT0), reg, now);

            assertEquals(1, out.size());
            var o = out.get(0);
            assertTrue(o.killed());
            assertEquals(RemoteSessionStateMachine.KillReason.TOKEN_REVOKED, o.reason());
            // SOURCE-BOUND: latency is anchored on the DB revoked_at exactly, not the event/app clock.
            long expected = Math.max(0, Duration.between(dbT0, now).toMillis());
            assertEquals(expected, o.latencyMillis());
            assertFalse(o.negativeLatency());
            assertFalse(o.storeUnavailable());
            latencies.add(o.latencyMillis());
        }

        Collections.sort(latencies);
        long p95 = percentile(latencies, 0.95);
        long p99 = percentile(latencies, 0.99);
        long max = latencies.get(latencies.size() - 1);
        assertTrue(p95 <= 5_000, "revocation P95 must be ≤ 5s, was " + p95 + "ms");
        assertTrue(p99 <= 10_000, "revocation P99 must be ≤ 10s, was " + p99 + "ms");
        assertTrue(max <= 30_000, "revocation max must be ≤ 30s, was " + max + "ms");
    }

    @Test
    void pollBackstopIsSourceBoundForADroppedEvent() {
        DbCasTokenLifecycleStore store = new DbCasTokenLifecycleStore(jdbc, SCHEMA);
        RemoteSessionRevocationReconciler reconciler = new RemoteSessionRevocationReconciler(
                store, new RemoteSessionHeartbeat(store, new RemoteSessionStateMachine(), Duration.ofSeconds(30)));

        String jti = "drop-1";
        Instant t = Instant.now();
        store.consume(jti, t.plus(Duration.ofHours(1)), t);
        store.revoke(jti);                                       // token revoked, but the push event was dropped
        Instant dbT0 = store.revokedAt(jti).orElseThrow();

        InMemorySessionRegistry reg = new InMemorySessionRegistry();
        reg.put(new RemoteSessionHeartbeat.SessionSnapshot("s", jti, RemoteSessionState.ACTIVE, 0L, Instant.now()));

        Instant now = Instant.now();
        var out = reconciler.pollReconcile(reg, now);
        assertEquals(1, out.size());
        var o = out.get(0);
        assertTrue(o.killed());
        assertTrue(o.feedDropRecovery());                        // the poll backstop caught the dropped revocation
        assertEquals(Math.max(0, Duration.between(dbT0, now).toMillis()), o.latencyMillis());
    }

    @Test
    void pollLeavesAStillLiveSessionUntouched() {
        DbCasTokenLifecycleStore store = new DbCasTokenLifecycleStore(jdbc, SCHEMA);
        RemoteSessionRevocationReconciler reconciler = new RemoteSessionRevocationReconciler(
                store, new RemoteSessionHeartbeat(store, new RemoteSessionStateMachine(), Duration.ofSeconds(30)));

        String jti = "live-1";
        Instant t = Instant.now();
        store.consume(jti, t.plus(Duration.ofHours(1)), t);      // USED + live, never revoked

        InMemorySessionRegistry reg = new InMemorySessionRegistry();
        reg.put(new RemoteSessionHeartbeat.SessionSnapshot("s", jti, RemoteSessionState.ACTIVE, 0L, Instant.now()));

        var out = reconciler.pollReconcile(reg, Instant.now());
        assertEquals(1, out.size());
        assertFalse(out.get(0).killed());
    }

    /** Nearest-rank percentile over a pre-sorted ascending list. */
    private static long percentile(List<Long> sortedAsc, double p) {
        if (sortedAsc.isEmpty()) {
            return 0;
        }
        int rank = (int) Math.ceil(p * sortedAsc.size());
        int idx = Math.min(Math.max(rank - 1, 0), sortedAsc.size() - 1);
        return sortedAsc.get(idx);
    }
}

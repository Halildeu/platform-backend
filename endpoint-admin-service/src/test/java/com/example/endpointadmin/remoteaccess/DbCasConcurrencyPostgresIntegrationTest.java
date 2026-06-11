package com.example.endpointadmin.remoteaccess;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
 * Faz 22.6 B2.2c — TRUE-concurrency proof of the DB-CAS store on real Postgres (Codex 019eb54b: the
 * @DataJpaTest IT proves the SQL sequentially; this proves the atomicity under genuine parallelism).
 * Uses a {@link DriverManagerDataSource} so every thread gets its own auto-commit connection (not a
 * single shared rollback transaction). Three combos: CONFLICT-consume, revoke-before, revoke-after.
 */
@Testcontainers
class DbCasConcurrencyPostgresIntegrationTest {

    private static final String SCHEMA = "endpoint_admin_service";
    private static final Instant T0 = Instant.parse("2026-06-11T10:00:00Z");
    private static final Instant EXP = T0.plus(Duration.ofHours(1));

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("endpoint_admin")
                    .withUsername("test")
                    .withPassword("test");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        ds.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + SCHEMA + ".remote_session_token ("
                + "jti VARCHAR(255) PRIMARY KEY, state VARCHAR(16) NOT NULL, "
                + "expires_at TIMESTAMPTZ NOT NULL, consumed_at TIMESTAMPTZ, revoked_at TIMESTAMPTZ, "
                + "created_at TIMESTAMPTZ NOT NULL DEFAULT now(), "
                + "CONSTRAINT chk_rst_state CHECK (state IN ('USED','REVOKED','EXPIRED','INVALID')))");
    }

    @AfterAll
    static void tearDown() {
        if (jdbc != null) {
            jdbc.execute("DROP TABLE IF EXISTS " + SCHEMA + ".remote_session_token");
        }
    }

    private DbCasTokenLifecycleStore store() {
        return new DbCasTokenLifecycleStore(jdbc, SCHEMA);
    }

    private int countAcceptedRacers(String jti, int threads, Runnable preRace) throws InterruptedException {
        DbCasTokenLifecycleStore store = store();
        if (preRace != null) {
            preRace.run();
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(threads, 32));
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger accepted = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (store.consume(jti, EXP, T0).isAccepted()) {
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
        assertTrue(done.await(30, TimeUnit.SECONDS), "threads did not finish");
        pool.shutdownNow();
        return accepted.get();
    }

    @Test
    void conflictConsumeAcceptsExactlyOnceUnderRealConcurrency() throws InterruptedException {
        // 64 committed connections racing the same jti → Postgres ON CONFLICT yields exactly one ACCEPTED.
        assertEquals(1, countAcceptedRacers("race-conflict", 64, null));
    }

    @Test
    void revokeBeforeConsumeRaceNeverAccepts() throws InterruptedException {
        // pre-emptive revoke wins: no racer may ACCEPT a jti revoked before the race.
        String jti = "race-revoke-before";
        assertEquals(0, countAcceptedRacers(jti, 48, () -> store().revoke(jti)));
        assertEquals(TokenLifecycleStore.TokenLiveCheckResult.REVOKED, store().isTokenLive(jti, T0));
    }

    @Test
    void simultaneousConsumeAndRevokeRaceNeverDoubleAcceptsAndRevokeWins() throws InterruptedException {
        // Codex 019eb54b: the prior combos sequence revoke/consume; this fires consume AND revoke threads
        // at the SAME barrier on the same jti. Invariants under any interleaving: ≤1 ACCEPTED, and since
        // revoke is authoritative + always-wins, the final state is REVOKED (not live).
        String jti = "race-simultaneous";
        DbCasTokenLifecycleStore store = store();
        int consumers = 32;
        int revokers = 16;
        int total = consumers + revokers;
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(total);
        AtomicInteger accepted = new AtomicInteger();
        for (int i = 0; i < consumers; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (store.consume(jti, EXP, T0).isAccepted()) {
                        accepted.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        for (int i = 0; i < revokers; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    store.revoke(jti);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "threads did not finish");
        pool.shutdownNow();
        assertTrue(accepted.get() <= 1, "single-use violated under simultaneous race: " + accepted.get());
        // revoke is authoritative → after the race the token is REVOKED (not live) regardless of interleaving
        assertEquals(TokenLifecycleStore.TokenLiveCheckResult.REVOKED, store.isTokenLive(jti, T0));
    }

    @Test
    void revokeAfterConsumeKillsTheLiveSession() throws InterruptedException {
        // the single winner is live, then a concurrent revoke flips it → the heartbeat kill path closes.
        String jti = "race-revoke-after";
        assertEquals(1, countAcceptedRacers(jti, 32, null));
        DbCasTokenLifecycleStore store = store();
        assertEquals(TokenLifecycleStore.TokenLiveCheckResult.LIVE, store.isTokenLive(jti, T0));
        assertEquals(TokenLifecycleStore.MutationOutcome.UPDATED, store.revoke(jti));
        // heartbeat sample after revoke → tokenBound=false → ABORTED/TOKEN_REVOKED (t3 kill)
        RemoteSessionStateMachine sm = new RemoteSessionStateMachine();
        RemoteSessionPreconditions afterRevoke = new RemoteSessionPreconditions(
                true, true, true, store.isTokenLive(jti, T0).isLive(), true, true);
        RemoteSessionStateMachine.Reevaluation r = sm.reevaluateActive(RemoteSessionState.ACTIVE, afterRevoke);
        assertEquals(RemoteSessionState.ABORTED, r.target());
        assertEquals(RemoteSessionStateMachine.KillReason.TOKEN_REVOKED, r.reason());
    }
}

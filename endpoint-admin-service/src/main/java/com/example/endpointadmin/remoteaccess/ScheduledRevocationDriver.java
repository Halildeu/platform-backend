package com.example.endpointadmin.remoteaccess;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.example.endpointadmin.remoteaccess.RemoteAccessMetrics.CLEANUP_DURATION_MS;
import static com.example.endpointadmin.remoteaccess.RemoteAccessMetrics.CLEANUP_PURGED_ROWS;
import static com.example.endpointadmin.remoteaccess.RemoteAccessMetrics.HARD_KILL_POLL_RECOVERY;
import static com.example.endpointadmin.remoteaccess.RemoteAccessMetrics.REVOCATION_CLOCK_SKEW;
import static com.example.endpointadmin.remoteaccess.RemoteAccessMetrics.REVOCATION_LATENCY_MS;
import static com.example.endpointadmin.remoteaccess.RemoteAccessMetrics.REVOCATION_NEGATIVE_LATENCY;
import static com.example.endpointadmin.remoteaccess.RemoteAccessMetrics.SESSION_OWNERSHIP_CONFLICT;
import static com.example.endpointadmin.remoteaccess.RemoteAccessMetrics.STORE_UNAVAILABLE;

/**
 * Faz 22.6 B2.2c — the live-runtime DRIVER: the thin {@code @Scheduled} Spring glue that turns the pure
 * {@link RemoteSessionRevocationReconciler} into a running multi-instance-safe revocation→hard-kill loop
 * and projects its outcomes onto metrics. <b>Disabled by default</b> ({@link ConditionalOnProperty}
 * {@code endpoint-admin.remote-access.enabled} absent ⇒ this bean is NOT created), so it is inert until
 * the ADR-0034 #1388 / D10 live-acceptance gate. The logic is all in the reconciler (unit + Testcontainers
 * proven); this surface only schedules + meters.
 *
 * <ul>
 *   <li><b>PUSH</b> — subscribes {@link TokenRevocationFeed}; on a revocation, reconciles immediately
 *       (low-latency path, SLO P95 ≤ 5s).</li>
 *   <li><b>POLL</b> — a short fixed-delay sweep ({@code ≤ poll-interval-ms}, default 2s) re-evaluates every
 *       local ACTIVE session so a DROPPED feed delivery still kills within the SLO (criterion #7).</li>
 *   <li><b>CLEANUP</b> — a rare sweep purges expired non-REVOKED tokens under a Postgres
 *       {@code pg_try_advisory_xact_lock} so exactly one replica runs it (no concurrent-DELETE contention,
 *       Codex Q4); the lock auto-releases at transaction end (no cross-pooled-connection unlock bug).</li>
 * </ul>
 *
 * <p>This slice has no tunnel runtime yet, so nothing registers sessions or publishes revocations while
 * disabled. The {@link #registry()} + {@link #feed()} hooks are where the C/D tunnel runtime will attach.
 */
@Component
@ConditionalOnProperty(name = "endpoint-admin.remote-access.enabled", havingValue = "true")
public class ScheduledRevocationDriver {

    private static final Logger log = LoggerFactory.getLogger(ScheduledRevocationDriver.class);

    /** Stable key for the cleanup advisory lock (one cleaner cluster-wide). Arbitrary but fixed. */
    private static final long CLEANUP_ADVISORY_LOCK_KEY = 0x52615f636c6e75L; // "Ra_clnu"

    private final JdbcTemplate jdbc;
    private final MeterRegistry meters;
    private final Clock clock;

    private final InMemorySessionRegistry registry;
    private final InMemoryTokenRevocationFeed feed;
    private final RemoteSessionRevocationReconciler reconciler;
    private final RemoteSessionTokenCleanup cleanup;
    private final Timer revocationLatency;

    public ScheduledRevocationDriver(
            JdbcTemplate jdbc,
            MeterRegistry meters,
            @Value("${spring.jpa.properties.hibernate.default_schema:endpoint_admin_service}") String schema,
            @Value("${endpoint-admin.remote-access.max-heartbeat-age-ms:15000}") long maxHeartbeatAgeMs) {
        this.jdbc = jdbc;
        this.meters = meters;
        this.clock = Clock.systemUTC();
        if (schema == null || !schema.matches("[a-z_][a-z0-9_]*")) {
            throw new IllegalArgumentException("invalid schema identifier: " + schema);
        }
        // Build the prod collaborators (DB-CAS store + heartbeat brain + reconciler) from primitives so no
        // remote-access bean leaks into the context while disabled. Registry + feed are local to this
        // replica (the single-owner locality the lock-free model relies on).
        TokenLifecycleStore store = new DbCasTokenLifecycleStore(jdbc, schema);
        RemoteSessionHeartbeat heartbeat = new RemoteSessionHeartbeat(
                store, new RemoteSessionStateMachine(), Duration.ofMillis(maxHeartbeatAgeMs));
        this.registry = new InMemorySessionRegistry();
        this.feed = new InMemoryTokenRevocationFeed();
        this.reconciler = new RemoteSessionRevocationReconciler(store, heartbeat);
        this.cleanup = new RemoteSessionTokenCleanup(jdbc, schema);
        this.revocationLatency = Timer.builder(REVOCATION_LATENCY_MS)
                .description("t0(revoked_at) → hard-kill decision latency (SLO P95 ≤ 5s)")
                .publishPercentiles(0.95, 0.99)
                .register(meters);
    }

    /** The replica-local session registry — the C/D tunnel runtime registers/removes sessions here. */
    public InMemorySessionRegistry registry() {
        return registry;
    }

    /** The revocation feed — an operator-abort / policy-change publishes a {@code RevocationEvent} here. */
    public InMemoryTokenRevocationFeed feed() {
        return feed;
    }

    @PostConstruct
    void subscribePushPath() {
        // PUSH: a metering/handling glitch must NEVER propagate to the publisher (the revocation source);
        // the poll backstop catches anything the push path drops (fail-closed).
        feed.subscribe(event -> {
            try {
                handleOutcomes(reconciler.onRevocation(event, registry, Instant.now(clock)));
            } catch (RuntimeException ex) {
                log.warn("remote-access push-reconcile failed errorClass={}", ex.getClass().getSimpleName());
            }
        });
        log.info("remote-access revocation driver ENABLED (push subscribed; poll + advisory-locked cleanup scheduled)");
    }

    @Scheduled(
            fixedDelayString = "${endpoint-admin.remote-access.poll-interval-ms:2000}",
            initialDelayString = "${endpoint-admin.remote-access.poll-initial-delay-ms:5000}")
    public void poll() {
        try {
            handleOutcomes(reconciler.pollReconcile(registry, Instant.now(clock)));
        } catch (RuntimeException ex) {
            log.warn("remote-access poll failed errorClass={}", ex.getClass().getSimpleName());
        }
    }

    @Scheduled(
            fixedDelayString = "${endpoint-admin.remote-access.cleanup-interval-ms:3600000}",
            initialDelayString = "${endpoint-admin.remote-access.cleanup-initial-delay-ms:60000}")
    @Transactional
    public void cleanup() {
        long start = System.nanoTime();
        // Exactly one replica wins the advisory lock; the lock is xact-scoped so it auto-releases at tx end
        // (no manual unlock, no cross-pooled-connection bug). Losers return immediately.
        Boolean locked = jdbc.queryForObject(
                "SELECT pg_try_advisory_xact_lock(?)", Boolean.class, CLEANUP_ADVISORY_LOCK_KEY);
        if (!Boolean.TRUE.equals(locked)) {
            log.debug("remote-access cleanup skipped — another replica holds the advisory lock");
            return;
        }
        try {
            int purged = cleanup.purgeExpired(Instant.now(clock));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            if (purged > 0) {
                meters.counter(CLEANUP_PURGED_ROWS).increment(purged);
            }
            meters.timer(CLEANUP_DURATION_MS).record(elapsedMs, TimeUnit.MILLISECONDS);
            log.info("remote-access cleanup purged={} elapsed_ms={}", purged, elapsedMs);
        } catch (RuntimeException ex) {
            log.warn("remote-access cleanup failed errorClass={}", ex.getClass().getSimpleName());
        }
    }

    /**
     * Project a batch of reconcile outcomes onto metrics + drop killed sessions from the local registry
     * (so a kill is counted once and the poll backstop doesn't re-process it). Only reliable, non-store-down
     * latency samples feed the SLO timer; negative-latency + store-unavailable kills are metered separately.
     */
    private void handleOutcomes(List<RemoteSessionRevocationReconciler.ReconcileOutcome> outcomes) {
        for (RemoteSessionRevocationReconciler.ReconcileOutcome o : outcomes) {
            if (o.ownershipConflict()) {
                meters.counter(SESSION_OWNERSHIP_CONFLICT).increment();
            }
            if (o.eventDbSkewMillis() > 0) {
                meters.counter(REVOCATION_CLOCK_SKEW).increment();
            }
            if (!o.killed()) {
                continue; // a still-live session in a poll sweep — nothing to do
            }
            if (o.storeUnavailable()) {
                meters.counter(STORE_UNAVAILABLE).increment(); // fail-closed kill, excluded from the SLO
            } else if (o.negativeLatency()) {
                meters.counter(REVOCATION_NEGATIVE_LATENCY).increment(); // unreliable sample, excluded
            } else {
                revocationLatency.record(o.latencyMillis(), TimeUnit.MILLISECONDS); // the SLO sample
            }
            if (o.feedDropRecovery()) {
                meters.counter(HARD_KILL_POLL_RECOVERY).increment();
            }
            registry.remove(o.sessionId());
        }
    }
}

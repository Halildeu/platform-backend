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
import static com.example.endpointadmin.remoteaccess.RemoteAccessMetrics.CLEANUP_LOCK_CONTENDED;
import static com.example.endpointadmin.remoteaccess.RemoteAccessMetrics.CLEANUP_PURGED_ROWS;
import static com.example.endpointadmin.remoteaccess.RemoteAccessMetrics.HARD_KILL_POLL_RECOVERY;
import static com.example.endpointadmin.remoteaccess.RemoteAccessMetrics.HARD_KILL_TOTAL;
import static com.example.endpointadmin.remoteaccess.RemoteAccessMetrics.LEGACY_UNBOUND_ISSUANCE;
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
    private final CertBoundConsumeGate consumeGate;
    private final Timer revocationLatency;
    /** B1.3b: whether an expected builder + policy hash were configured (else the verifier is deny-all). */
    private final boolean attestationPolicyConfigured;

    public ScheduledRevocationDriver(
            JdbcTemplate jdbc,
            MeterRegistry meters,
            RemoteAccessProperties properties,
            @Value("${spring.jpa.properties.hibernate.default_schema:endpoint_admin_service}") String schema,
            @Value("${endpoint-admin.remote-access.max-heartbeat-age-ms:15000}") long maxHeartbeatAgeMs,
            @Value("${endpoint-admin.remote-access.cert-trust.max-age-ms:3600000}") long certTrustMaxAgeMs,
            @Value("${endpoint-admin.remote-access.attestation.expected-builder-id:}") String expectedBuilderId,
            @Value("${endpoint-admin.remote-access.attestation.expected-policy-hash:}") String expectedPolicyHash,
            @Value("${endpoint-admin.remote-access.cert-trust.expected-issuer-dn:}") String expectedIssuerDn) {
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
        CertBindingGuard.Policy certPolicy = properties.getCertBinding().policy();
        // B1.2 cert-trust source. In-memory reference for now (chain/CRL/OCSP modeled); the B1.4 transport
        // seam swaps in real X.509 path-build + live CRL/OCSP. Never consulted until a cert-sampling
        // heartbeat runs under an enabled runtime (disabled-by-default).
        CertTrustEvaluator trustEvaluator =
                new InMemoryCertTrustEvaluator(Duration.ofMillis(certTrustMaxAgeMs));
        // B1.3b agent-attestation source. In-memory reference (SLSA/builder/signed-predicate modeled — Codex
        // 019eb694 placeholder trust basis); the B1.4 transport seam swaps in real Sigstore/cosign + SLSA
        // envelope verify. With NO configured expected builder/policy the verifier is left null → the
        // heartbeat coerces it to deny-all (fail-closed: an enabled runtime without an attestation policy
        // refuses every live session until D10 supplies the expected builder + policy hash). Never consulted
        // while disabled-by-default (no cert-sampling heartbeat runs).
        AttestationVerifier attestationVerifier =
                (expectedBuilderId == null || expectedBuilderId.isBlank()
                        || expectedPolicyHash == null || expectedPolicyHash.isBlank())
                        ? null
                        : new InMemoryAttestationVerifier(expectedBuilderId, expectedPolicyHash);
        this.attestationPolicyConfigured = attestationVerifier != null;
        // B1.4a-0 cert-identity pin. An operator-configured expected agent-CA issuer DN; null when blank →
        // identity NOT enforced (an ADDITIVE opt-in hardening on top of binding+trust, so absence is a
        // legitimate "not constrained", NOT fail-closed). When set, the heartbeat rejects any presented cert
        // from another CA. Serial is per-cert (pinned by the bound token later, B1.4a/store) — not configured
        // here. Real RFC 4514 DN canonicalisation + X.509 path-build is the B1.4a PKIX slice.
        CertRef expectedCertIdentity = (expectedIssuerDn == null || expectedIssuerDn.isBlank())
                ? null
                : new CertRef(null, "SHA-256", null, expectedIssuerDn);
        RemoteSessionHeartbeat heartbeat = new RemoteSessionHeartbeat(
                store, new RemoteSessionStateMachine(), Duration.ofMillis(maxHeartbeatAgeMs), certPolicy,
                trustEvaluator, attestationVerifier, expectedCertIdentity);
        this.registry = new InMemorySessionRegistry();
        this.feed = new InMemoryTokenRevocationFeed();
        this.reconciler = new RemoteSessionRevocationReconciler(store, heartbeat);
        this.cleanup = new RemoteSessionTokenCleanup(jdbc, schema);
        // The connect-time consume enforcement (B1.1c): the C/D tunnel runtime consumes ONLY through this
        // gate, so the cert-binding policy + the legacy-unbound migration meter are already live wiring.
        this.consumeGate = new CertBoundConsumeGate(
                store, certPolicy, () -> meters.counter(LEGACY_UNBOUND_ISSUANCE).increment());
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

    /** The cert-binding consume gate (B1.1c) — the C/D tunnel runtime claims connect tokens ONLY here. */
    public CertBoundConsumeGate consumeGate() {
        return consumeGate;
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
        // B1.3b operability (Codex 019eb6d2 #3): an enabled runtime with NO configured attestation policy is
        // a deliberate global fail-closed (every cert-sampling session is DENIED) — but a silent config drift
        // would read as a mysterious blanket outage. Announce it loudly so ops sees the cause, not just the
        // symptom. (A full readiness/health-indicator that GATES live sessions on this belongs to the C/D
        // tunnel-runtime slice that actually opens cert-sampling sessions; the reconciler here is
        // cert-unsampled and never consults the verifier.)
        if (!attestationPolicyConfigured) {
            log.warn("remote-access ENABLED but NO attestation policy configured "
                    + "(endpoint-admin.remote-access.attestation.expected-builder-id / expected-policy-hash blank) "
                    + "— every cert-sampling session will be DENIED (fail-closed) until configured; "
                    + "this is a D10 live-acceptance prerequisite");
        }
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
            meters.counter(CLEANUP_LOCK_CONTENDED).increment();
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
            meters.counter(HARD_KILL_TOTAL).increment(); // denominator for the unavailable/unmeasured ratios
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

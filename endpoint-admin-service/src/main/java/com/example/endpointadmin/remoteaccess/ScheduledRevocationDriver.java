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

import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CRL;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
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
            @Value("${endpoint-admin.remote-access.attestation.verifier:IN_MEMORY}") String attestationVerifierType,
            @Value("${endpoint-admin.remote-access.attestation.public-key-pem:}") String attestationPublicKeyPem,
            @Value("${endpoint-admin.remote-access.attestation.signature-algorithm:SHA256withECDSA}")
            String attestationSignatureAlgorithm,
            @Value("${endpoint-admin.remote-access.cert-trust.expected-issuer-dn:}") String expectedIssuerDn,
            @Value("${endpoint-admin.remote-access.cert-trust.evaluator:IN_MEMORY}") String certEvaluatorType,
            @Value("${endpoint-admin.remote-access.cert-trust.revocation-mode:DISABLED}") String certRevocationMode,
            @Value("${endpoint-admin.remote-access.cert-trust.trust-anchor-pem:}") String certTrustAnchorPem,
            @Value("${endpoint-admin.remote-access.cert-trust.crl-pem:}") String certCrlPem,
            @Value("${endpoint-admin.remote-access.cert-trust.allow-insecure-no-revocation:false}")
            boolean certAllowInsecureNoRevocation,
            @Value("${spring.profiles.active:}") String activeProfiles) {
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
        // B1.2/B1.4a-3 cert-trust source, SELECTED by config (default IN_MEMORY). REAL_PKI runs the real JDK
        // PKIX path-build (B1.4a-2) + EKU enforcement, but the factory enforces the Codex 019eb6d9 blocking
        // matrix AT THIS CONSTRUCTION (= startup): REAL_PKI fails fast on empty anchors, and is legal only with
        // CRL/OCSP (B1.4b) — a REAL_PKI+DISABLED boot is refused unless the test-only escape is set. So a
        // mis-config FAILS the bean rather than silently always-NOT_TRUSTED. Never consulted while
        // disabled-by-default (no cert-sampling heartbeat runs).
        // a "production-like" profile refuses the no-revocation test escape even if its flag is set (Codex
        // 019eb6d9): a stray test flag must never weaken a prod runtime.
        boolean productionLike = activeProfiles != null
                && activeProfiles.toLowerCase(java.util.Locale.ROOT).contains("prod");
        CertTrustEvaluator trustEvaluator = buildTrustEvaluator(
                certEvaluatorType, certRevocationMode, certTrustAnchorPem, certCrlPem,
                certAllowInsecureNoRevocation, productionLike, certTrustMaxAgeMs);
        // B1.3b/B1.4c-3 agent-attestation source, SELECTED by config (default IN_MEMORY placeholder). KEY_BASED
        // (B1.4c-1) swaps in REAL signature verification against the configured public key; the factory
        // enforces the blocking matrix AT THIS CONSTRUCTION (= startup): the placeholder is forbidden in a
        // prod-like profile, KEY_BASED fails fast without a key, DSSE (B1.4c-2) is refused until the C/D
        // transport delivers the envelope. With NO configured expected builder/policy the verifier is null →
        // the heartbeat coerces it to deny-all (an enabled runtime without an attestation policy refuses every
        // cert-sampling session until D10). Never consulted while disabled-by-default.
        AttestationVerifier attestationVerifier = buildAttestationVerifier(
                attestationVerifierType, expectedBuilderId, expectedPolicyHash,
                attestationPublicKeyPem, attestationSignatureAlgorithm, productionLike);
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

    /**
     * Select + safely construct the cert-trust evaluator from config (B1.4a-3). Invalid enum values and an
     * unparseable anchor bundle FAIL FAST here (= startup), as does any forbidden REAL_PKI combination (via
     * {@link CertTrustEvaluatorFactory}). Blank evaluator/mode default to the safe IN_MEMORY/DISABLED.
     */
    private static CertTrustEvaluator buildTrustEvaluator(String evaluatorType, String revocationMode,
                                                          String trustAnchorPem, String trustCrlPem,
                                                          boolean allowInsecureNoRevocation,
                                                          boolean productionLikeProfile, long inMemoryMaxAgeMs) {
        CertTrustEvaluatorFactory.EvaluatorType type = parseEnum(
                evaluatorType, CertTrustEvaluatorFactory.EvaluatorType.class,
                CertTrustEvaluatorFactory.EvaluatorType.IN_MEMORY);
        CertTrustEvaluatorFactory.RevocationMode mode = parseEnum(
                revocationMode, CertTrustEvaluatorFactory.RevocationMode.class,
                CertTrustEvaluatorFactory.RevocationMode.DISABLED);
        Set<TrustAnchor> anchors;
        try {
            anchors = TrustAnchorLoader.fromPemBundle(trustAnchorPem);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(
                    "remote-access cert-trust.trust-anchor-pem is not a valid PEM certificate bundle", e);
        }
        List<X509CRL> crls;
        try {
            crls = X509ChainParser.parseCrlBundle(trustCrlPem);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(
                    "remote-access cert-trust.crl-pem is not a valid PEM CRL bundle", e);
        }
        return CertTrustEvaluatorFactory.create(
                type, mode, anchors, crls, allowInsecureNoRevocation, productionLikeProfile,
                Duration.ofMillis(inMemoryMaxAgeMs));
    }

    /**
     * Select + safely construct the attestation verifier from config (B1.4c-3). An invalid verifier enum, an
     * unparseable public-key PEM, or any forbidden combination (via {@link AttestationVerifierFactory}) FAIL
     * FAST here (= startup). Blank expected builder/policy → {@code null} (heartbeat deny-all).
     */
    private static AttestationVerifier buildAttestationVerifier(String verifierType, String expectedBuilderId,
                                                               String expectedPolicyHash, String publicKeyPem,
                                                               String signatureAlgorithm,
                                                               boolean productionLikeProfile) {
        AttestationVerifierFactory.VerifierType type = parseEnum(
                verifierType, AttestationVerifierFactory.VerifierType.class,
                AttestationVerifierFactory.VerifierType.IN_MEMORY);
        PublicKey signingKey = null;
        if (publicKeyPem != null && !publicKeyPem.isBlank()) {
            try {
                signingKey = PublicKeys.fromPem(publicKeyPem);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(
                        "remote-access attestation.public-key-pem is not a valid public key", e);
            }
        }
        return AttestationVerifierFactory.create(
                type, expectedBuilderId, expectedPolicyHash, signingKey, signatureAlgorithm,
                productionLikeProfile);
    }

    /** Parse a config enum, blank→default, an invalid value → fail-fast (a typo must not silently default). */
    private static <E extends Enum<E>> E parseEnum(String value, Class<E> type, E dflt) {
        if (value == null || value.isBlank()) {
            return dflt;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "invalid remote-access config value '" + value + "' for " + type.getSimpleName());
        }
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

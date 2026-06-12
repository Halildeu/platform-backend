package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.AttestationVerifier;
import com.example.endpointadmin.remoteaccess.CertTrustEvaluator;
import com.example.endpointadmin.remoteaccess.DbRecordingSink;
import com.example.endpointadmin.remoteaccess.DeviceIdentityVerifier;
import com.example.endpointadmin.remoteaccess.RecordingAnchorSigner;
import com.example.endpointadmin.remoteaccess.RemoteAccessVerifierFactory;
import com.example.endpointadmin.remoteaccess.RemoteSessionPolicyEngine;
import com.example.endpointadmin.remoteaccess.SessionRecorder;
import com.example.endpointadmin.remoteaccess.SessionRecordingChain;
import com.example.endpointadmin.remoteaccess.bridge.DurableRemoteBridgeAuditSink;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeAuditSink;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeBroker;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgePermitSigner;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.BrokerControlPlane;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.OwnerTokenGate;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.PeerEvidenceParser;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.PeerTrustLedger;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeOperatorService;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeSessionStore;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.TrustEvidenceAssembler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.security.PrivateKey;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Faz 22.6 T-2b (Codex 019eb9fb) — remote-bridge server wiring, conditional on
 * {@code remote-bridge.enabled=true} with NO default ({@code matchIfMissing=false}): the default application
 * context contains ZERO remote-bridge beans — not even the properties holder
 * ({@code @EnableConfigurationProperties} lives INSIDE the conditional class, Codex T-2b). Same disabled-by-
 * default pattern as {@code ScheduledRevocationDriver}.
 *
 * <p>The {@link ControlPlaneHandler} stays {@code INERT} in T-2b — broker/policy wiring (SessionContext
 * assembly, trust evidence, permits) is the owner-gated T-4 slice.
 */
@Configuration
@ConditionalOnProperty(prefix = "remote-bridge", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(RemoteBridgeServerProperties.class)
public class RemoteBridgeServerConfig {

    /**
     * Faz 22.6 T-4a-ii (Codex 019ebc7e) — the broker permit-signing key, loaded fail-closed at context init:
     * an enabled bridge that cannot sign permits refuses to start (no insecure escape). The broker bean
     * (slice-2/3) consumes this signer; until then the eager singleton still runs the fail-closed key
     * validation, so "enabled boot requires a valid signing key" holds today.
     */
    @Bean
    public RemoteBridgePermitSigner remoteBridgePermitSigner(RemoteBridgeServerProperties properties) {
        return PermitSigningKeyLoader.load(properties.permit());
    }

    /**
     * Faz 22.6 T-4a-ii slice-2b (Codex 019ebc7e) — the broker's per-peer trust ledger: the B1.4 verifiers
     * built the SAME way as the revocation runtime (shared {@link RemoteAccessVerifierFactory}, same
     * {@code endpoint-admin.remote-access.*} config), wrapped fail-closed (an unconfigured attestation policy
     * → deny-all, never null). The wire-format evidence parser stays {@link PeerEvidenceParser#FAIL_CLOSED}
     * (the real decoder is the owner-gated T-4 device-format slice). The ledger is consumed by the
     * BrokerControlPlane bean (slice-2c, INERT→real); until then it is constructed (and its config validated)
     * fail-closed at refresh.
     */
    @Bean
    public PeerTrustLedger remoteBridgePeerTrustLedger(
            Environment environment,
            @Value("${endpoint-admin.remote-access.cert-trust.evaluator:IN_MEMORY}") String certEvaluator,
            @Value("${endpoint-admin.remote-access.cert-trust.revocation-mode:DISABLED}") String certRevocationMode,
            @Value("${endpoint-admin.remote-access.cert-trust.trust-anchor-pem:}") String certTrustAnchorPem,
            @Value("${endpoint-admin.remote-access.cert-trust.crl-pem:}") String certCrlPem,
            @Value("${endpoint-admin.remote-access.cert-trust.allow-insecure-no-revocation:false}")
                    boolean certAllowInsecureNoRevocation,
            @Value("${endpoint-admin.remote-access.cert-trust.max-age-ms:3600000}") long certTrustMaxAgeMs,
            @Value("${endpoint-admin.remote-access.attestation.verifier:IN_MEMORY}") String attestationVerifier,
            @Value("${endpoint-admin.remote-access.attestation.expected-builder-id:}") String expectedBuilderId,
            @Value("${endpoint-admin.remote-access.attestation.expected-policy-hash:}") String expectedPolicyHash,
            @Value("${endpoint-admin.remote-access.attestation.public-key-pem:}") String attestationPublicKeyPem,
            @Value("${endpoint-admin.remote-access.attestation.signature-algorithm:SHA256withECDSA}")
                    String attestationSignatureAlgorithm,
            @Value("${remote-bridge.peer-trust.device-ca-pem:}") String deviceCaPem,
            @Value("${remote-bridge.peer-trust.device-protection-level:SECURE_ELEMENT_OR_TPM}")
                    String deviceProtectionLevel,
            @Value("${remote-bridge.peer-trust.freshness-ttl-millis:30000}") long freshnessTtlMillis) {
        // a prod-like profile refuses the test-only escapes (Codex 019eb6d9) — same rule as the driver
        String profiles = environment.getActiveProfiles().length == 0 ? "" : String.join(",",
                environment.getActiveProfiles()).toLowerCase(Locale.ROOT);
        boolean productionLike = profiles.contains("prod");
        CertTrustEvaluator certTrust = RemoteAccessVerifierFactory.buildTrustEvaluator(
                certEvaluator, certRevocationMode, certTrustAnchorPem, certCrlPem,
                certAllowInsecureNoRevocation, productionLike, certTrustMaxAgeMs);
        AttestationVerifier attestation = RemoteAccessVerifierFactory.buildAttestationVerifierOrDenyAll(
                attestationVerifier, expectedBuilderId, expectedPolicyHash, attestationPublicKeyPem,
                attestationSignatureAlgorithm, productionLike);
        DeviceIdentityVerifier device = RemoteAccessVerifierFactory.buildDeviceIdentityVerifier(
                deviceCaPem, deviceProtectionLevel);
        return new PeerTrustLedger(certTrust, attestation, device,
                PeerEvidenceParser.FAIL_CLOSED, freshnessTtlMillis);
    }

    @Bean
    public RemoteBridgeSessionStore remoteBridgeSessionStore() {
        return new RemoteBridgeSessionStore();
    }

    @Bean
    public ControlStreamRegistry remoteBridgeControlStreamRegistry() {
        return new ControlStreamRegistry();
    }

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService remoteBridgeHeartbeatScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "remote-bridge-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Faz 22.6 T-4a-ii slice-2c — the inbound-audit sink for the BrokerControlPlane. The control plane's
     * agent-event audit (hello-verified / consent / local-abort) is BEST-EFFORT (a record failure must never
     * make the broker IGNORE a consent denial or a kill — the safe outcome proceeds, BrokerControlPlane
     * swallows the throw). So a structured log line is the correct sink here. The DURABLE, hash-chained,
     * session-keyed recorder (the one {@code RemoteBridgeBroker}'s durable-record-BEFORE-permit rule needs)
     * is the slice-3 adapter — this best-effort log sink does NOT gate permit issuance (no broker yet).
     */
    @Bean
    public RemoteBridgeAuditSink remoteBridgeInboundAuditSink() {
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger("remote-bridge.audit.inbound");
        return event -> log.info("remote-bridge inbound audit: session={} type={} ts={}",
                event.sessionId(), event.eventType(), event.epochMillis());
    }

    /**
     * Faz 22.6 T-4a-ii slice-2c — the REAL inbound control plane: replaces {@code ControlPlaneHandler.INERT}.
     * Agent events (hello → ledger trust evidence; consent → session lease; local-abort/indicator-loss → kill)
     * are now absorbed into broker state. This issues NO authority — no consent prompts, no operation permits
     * (the operator-side service + permit push are slice-4). The transport still cannot mint a permit.
     */
    @Bean
    public BrokerControlPlane remoteBridgeControlPlane(PeerTrustLedger ledger,
                                                       RemoteBridgeSessionStore store,
                                                       @Qualifier("remoteBridgeInboundAuditSink")
                                                       RemoteBridgeAuditSink auditSink) {
        // pin the BEST-EFFORT inbound sink explicitly: slice-3c adds a second RemoteBridgeAuditSink bean (the
        // DURABLE broker recorder) — the control plane's inbound audit must stay the best-effort log sink, so
        // a durable-write failure can never make it ignore a consent denial / kill (the safe outcome proceeds)
        return new BrokerControlPlane(ledger, store, auditSink, System::currentTimeMillis);
    }

    /**
     * Faz 22.6 T-4a-ii slice-3c (Codex 019ebc7e) — the broker's DURABLE control-plane audit sink: one
     * hash-chained {@link SessionRecorder} per session ({@code chainId == sessionId}) over the EXISTING WORM
     * recording store ({@link DbRecordingSink}, Flyway V65). This is the sink that gates permit issuance — a
     * durable-write failure throws, so the broker issues no permit (ADR-0034 §6). Distinct from the
     * best-effort {@code remoteBridgeInboundAuditSink} (pinned to the control plane via {@code @Qualifier}).
     *
     * <p>The recording-anchor key is a SEPARATE forensic/WORM-integrity key (NOT the permit-signing key) —
     * its own config, rotation, and blast-radius domain (Codex S2). Loaded fail-closed via the shared
     * {@link Pkcs8EcPrivateKeyLoader}: an enabled broker with no readable anchor key refuses to start. The
     * {@code DbRecordingSink} constructor only holds the {@link JdbcTemplate} reference (no DB I/O at wiring);
     * its Postgres behaviour is proven in {@code DbRecordingSinkPostgresIntegrationTest}.
     */
    @Bean
    public DurableRemoteBridgeAuditSink remoteBridgeDurableAuditSink(
            JdbcTemplate jdbcTemplate,
            @Value("${ENDPOINT_ADMIN_DB_SCHEMA:endpoint_admin_service}") String schema,
            @Value("${remote-bridge.recording.anchor-key.path:}") String anchorKeyPath,
            @Value("${remote-bridge.recording.anchor-key.algorithm:SHA256withECDSA}") String anchorAlgorithm) {
        PrivateKey anchorKey =
                Pkcs8EcPrivateKeyLoader.loadFromFile(anchorKeyPath, "remote-bridge.recording.anchor-key.path");
        // Codex slice-3c REVISE: prevalidate at refresh so an enabled boot really IS config-validated — a
        // malformed schema or a bad/incompatible anchor algorithm must fail HERE, not lazily at the first record.
        try {
            // schema identifier guard: DbRecordingSink's ctor runs the [a-z_][a-z0-9_]* SQL-identifier check
            // (null/blank/uppercase/dotted all rejected), so a malformed schema fails at refresh. The probe
            // holds the JdbcTemplate reference only — no DB I/O at construction.
            new DbRecordingSink("__startup_probe__", jdbcTemplate, schema);
        } catch (RuntimeException e) {
            throw new IllegalStateException("ENDPOINT_ADMIN_DB_SCHEMA is invalid for an enabled broker ("
                    + schema + ") — refusing to start", e);
        }
        try {
            // startup probe — the ctor runs SignatureAlgorithms.require(alg) (allowlist), and a real anchor
            // over an empty chain runs Signature.initSign(anchorKey), so an UNSUPPORTED alg AND an alg that is
            // allowed-but-incompatible with the key (e.g. an EC key + SHA256withRSA) both fail at refresh, not
            // lazily at the first record (Codex slice-3c REVISE-2). The probe instances are discarded.
            new RecordingAnchorSigner("__startup_probe__", anchorKey, anchorAlgorithm)
                    .anchor(new SessionRecordingChain(), 0L);
        } catch (RuntimeException e) {
            throw new IllegalStateException("remote-bridge.recording.anchor-key.algorithm is invalid or "
                    + "incompatible with the anchor key for an enabled broker (" + anchorAlgorithm
                    + ") — refusing to start", e);
        }
        return new DurableRemoteBridgeAuditSink(sessionId -> new SessionRecorder(
                new DbRecordingSink(sessionId, jdbcTemplate, schema),
                new RecordingAnchorSigner(sessionId, anchorKey, anchorAlgorithm)));
    }

    /**
     * Faz 22.6 T-4a-ii slice-3c — the BROKER: composes the merged pilot policy engine
     * ({@link RemoteSessionPolicyEngine#PILOT}) + the permit signer + the DURABLE audit sink into the
     * record-BEFORE-permit control plane (ADR-0038, T-1b). The bean exists ONLY when {@code
     * remote-bridge.enabled=true}; it is constructed + config-validated here but issues authority to NO
     * transport — the operator-side service + permit push that drive it are slice-4. So an enabled boot proves
     * "the broker wires fail-closed (valid signer + durable recorder + pilot engine)" without minting permits.
     */
    @Bean
    public RemoteBridgeBroker remoteBridgeBroker(
            RemoteBridgePermitSigner remoteBridgePermitSigner,
            DurableRemoteBridgeAuditSink remoteBridgeDurableAuditSink,
            @Value("${remote-bridge.broker.policy-version:rb-pilot-v1}") String policyVersion,
            @Value("${remote-bridge.broker.permit-ttl-millis:60000}") long permitTtlMillis) {
        return new RemoteBridgeBroker(true, RemoteSessionPolicyEngine.PILOT, remoteBridgePermitSigner,
                remoteBridgeDurableAuditSink, policyVersion, permitTtlMillis);
    }

    /**
     * Faz 22.6 T-4a-ii slice-4b — the trust-evidence assembler the operator service feeds the broker.
     * {@code OwnerTokenGate.DENY_ALL}: no owner-signed-token verifier until the live-pilot slice → grant
     * nothing (every operation denied for lack of capability). {@code DuressSignalSource.AMBIGUOUS_UNTIL_WIRED}:
     * no transport duress-classification path yet → AMBIGUOUS → the broker KILLS — fail-closed, an enabled
     * broker with no real duress source kills rather than proceeds.
     */
    @Bean
    public TrustEvidenceAssembler remoteBridgeTrustEvidenceAssembler(PeerTrustLedger remoteBridgePeerTrustLedger) {
        return new TrustEvidenceAssembler(remoteBridgePeerTrustLedger, OwnerTokenGate.DENY_ALL,
                TrustEvidenceAssembler.DuressSignalSource.AMBIGUOUS_UNTIL_WIRED);
    }

    /**
     * Faz 22.6 T-4a-ii slice-4b — the operator-side orchestration: drives an OperationRequest through the
     * broker and routes the verdict to the transport (slice-4a primitives). Wired here but the transport
     * endpoint that ACCEPTS operator requests is a later slice — this bean proves the broker↔transport seam
     * composes fail-closed. No authority is minted outside the broker.
     */
    @Bean
    public RemoteBridgeOperatorService remoteBridgeOperatorService(
            RemoteBridgeSessionStore remoteBridgeSessionStore,
            TrustEvidenceAssembler remoteBridgeTrustEvidenceAssembler,
            RemoteBridgeBroker remoteBridgeBroker,
            ControlStreamRegistry remoteBridgeControlStreamRegistry) {
        return new RemoteBridgeOperatorService(remoteBridgeSessionStore, remoteBridgeTrustEvidenceAssembler,
                remoteBridgeBroker, remoteBridgeControlStreamRegistry, System::currentTimeMillis);
    }

    @Bean
    public RemoteBridgeConnectService remoteBridgeConnectService(RemoteBridgeServerProperties properties,
                                                                 ControlStreamRegistry registry,
                                                                 ScheduledExecutorService heartbeatScheduler,
                                                                 BrokerControlPlane controlPlane) {
        return new RemoteBridgeConnectService(registry, controlPlane, heartbeatScheduler,
                properties.heartbeatIntervalMillis(), properties.maxDataFrameBytes(),
                System::currentTimeMillis, "rb-v1");
    }

    @Bean
    public RemoteBridgeGrpcServer remoteBridgeGrpcServer(RemoteBridgeServerProperties properties,
                                                         RemoteBridgeConnectService service,
                                                         ControlStreamRegistry registry) {
        return new RemoteBridgeGrpcServer(properties, service, registry);
    }
}

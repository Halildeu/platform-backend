package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.AttestationVerifier;
import com.example.endpointadmin.remoteaccess.ApprovedRemoteScriptCatalog;
import com.example.endpointadmin.remoteaccess.CertTrustEvaluator;
import com.example.endpointadmin.remoteaccess.DbRecordingSink;
import com.example.endpointadmin.remoteaccess.DeviceIdentityVerifier;
import com.example.endpointadmin.remoteaccess.OperatorStepUpPolicy.MethodStrength;
import com.example.endpointadmin.remoteaccess.OperatorStepUpVerifier;
import com.example.endpointadmin.remoteaccess.OperatorStepUpVerifierFactory;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.OperatorStepUpHandler;
import com.example.endpointadmin.remoteaccess.RecordingAnchorSigner;
import com.example.endpointadmin.remoteaccess.RemoteOperationCatalog;
import com.example.endpointadmin.remoteaccess.RemoteAccessVerifierFactory;
import com.example.endpointadmin.remoteaccess.RemoteSessionPolicyEngine;
import com.example.endpointadmin.remoteaccess.SessionRecorder;
import com.example.endpointadmin.remoteaccess.SessionRecordingChain;
import com.example.endpointadmin.remoteaccess.bridge.DurableRemoteBridgeAuditSink;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeAuditSink;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeBroker;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgePermitSigner;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.BrokerControlPlane;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.ConnectedDeviceResolver;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.DeviceKeyChallengeStore;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.TpmDeviceKeySessionEvidenceStore;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.ApprovalGrantStore;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.DuressSignalSourceFactory;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.InMemoryApprovalGrantStore;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.JdbcApprovalGrantStore;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.OwnerGrantGateFactory;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.OwnerTokenGate;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.PeerEvidenceParser;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.PeerEvidenceParserFactory;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.PeerTrustLedger;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeAgentErrorLedger;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeNegativeProbeService;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeOperatorService;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeSessionStore;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.SessionDeviceTrustVerifier;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.SessionDeviceTrustVerifierFactory;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.SessionDuressSignalStore;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.TrustEvidenceAssembler;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.SessionDeviceTrustVerifierFactory.DeviceKeyRealVerifierDependencies;
import com.example.endpointadmin.remoteaccess.bridge.server.viewonly.LiveOnlyViewDataPlaneHandler;
import com.example.endpointadmin.remoteaccess.bridge.server.viewonly.LoggingViewOnlyMetadataAuditSink;
import com.example.endpointadmin.remoteaccess.bridge.server.viewonly.ViewOnlyDataPlaneFactory;
import com.example.endpointadmin.remoteaccess.bridge.server.viewonly.ViewOnlyMetadataAuditSink;
import com.example.endpointadmin.remoteaccess.bridge.server.viewonly.ViewOnlySessionLifecycle;
import com.example.endpointadmin.remoteaccess.bridge.server.viewonly.ViewOnlyStreamAuthorizationRegistry;
import com.example.endpointadmin.remoteaccess.bridge.server.viewonly.ViewOnlyViewerRegistry;
import com.example.endpointadmin.repository.EndpointMachineCertRepository;
import com.example.endpointadmin.repository.EndpointTpmDeviceBindingRepository;
import com.example.endpointadmin.tpmattest.TpmEkChainValidator;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.security.PrivateKey;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static com.example.endpointadmin.remoteaccess.RemoteAccessMetrics.BRIDGE_CONTROL_STREAMS_CONNECTED;

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
     * → deny-all, never null). The wire-format evidence parser is selected by {@link PeerEvidenceParserFactory}
     * (D10.1 #634): DEFAULT {@code FAIL_CLOSED} (empty evidence → never PERMIT); {@code TRANSPORT_BOUND} (non-prod
     * pilot) decodes the CertRef from the mTLS transport leaf + the agent attestation so the real verifiers run.
     * The ledger is consumed by the BrokerControlPlane bean (slice-2c, INERT→real); until then it is constructed
     * (and its config validated) fail-closed at refresh.
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
            @Value("${remote-bridge.peer-trust.freshness-ttl-millis:30000}") long freshnessTtlMillis,
            @Value("${remote-bridge.peer-evidence.parser:FAIL_CLOSED}") String peerEvidenceParserType) {
        // a prod-like profile refuses the test-only escapes (Codex 019eb6d9) — same rule as the driver
        boolean productionLike = isProductionLike(environment);
        CertTrustEvaluator certTrust = RemoteAccessVerifierFactory.buildTrustEvaluator(
                certEvaluator, certRevocationMode, certTrustAnchorPem, certCrlPem,
                certAllowInsecureNoRevocation, productionLike, certTrustMaxAgeMs);
        AttestationVerifier attestation = RemoteAccessVerifierFactory.buildAttestationVerifierOrDenyAll(
                attestationVerifier, expectedBuilderId, expectedPolicyHash, attestationPublicKeyPem,
                attestationSignatureAlgorithm, productionLike);
        DeviceIdentityVerifier device = RemoteAccessVerifierFactory.buildDeviceIdentityVerifier(
                deviceCaPem, deviceProtectionLevel);
        // D10.1 (#634, Codex 019ec29a): the wire-format evidence PARSER is now selectable. DEFAULT FAIL_CLOSED
        // (empty evidence → every trust false → never PERMIT — behaviour unchanged); TRANSPORT_BOUND (non-prod
        // pilot) decodes the CertRef from the mTLS transport leaf + the agent's attestation so the REAL verifiers
        // above can run. The parser produces only EVIDENCE — never a trust input; the verifiers still decide
        // cert/attestation/device trust. A prod-like profile refuses the pilot parser (its attestation wire-form
        // is synthetic-agent-specific).
        PeerEvidenceParser peerEvidenceParser =
                PeerEvidenceParserFactory.create(peerEvidenceParserType, productionLike);
        return new PeerTrustLedger(certTrust, attestation, device,
                peerEvidenceParser, freshnessTtlMillis);
    }

    @Bean
    public RemoteBridgeSessionStore remoteBridgeSessionStore() {
        return new RemoteBridgeSessionStore();
    }

    @Bean
    public ControlStreamRegistry remoteBridgeControlStreamRegistry() {
        return new ControlStreamRegistry();
    }

    /**
     * Faz 22.6 #548 slice-1 step-3 — the broker-nonced device-key challenge store (issue + single-use/TTL
     * consume). Wired here so the control plane can consume responses; the issuance trigger (step-5b) issues a
     * challenge per opened session.
     */
    @Bean
    public DeviceKeyChallengeStore remoteBridgeDeviceKeyChallengeStore() {
        return new DeviceKeyChallengeStore();
    }

    /**
     * Faz 22.6 #548 slice-1 step-5 — the session device-key evidence store, keyed by {@code (sessionId,
     * transportPeerKey)} with a challenge-expiry TTL. The control plane populates it from a consumed
     * challenge-response; the {@code DEVICE_KEY_ATTESTATION_REAL} verifier reads it at PERMIT time. Holds no
     * trust by itself.
     */
    @Bean
    public TpmDeviceKeySessionEvidenceStore remoteBridgeDeviceKeySessionEvidenceStore() {
        return new TpmDeviceKeySessionEvidenceStore();
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

    @Bean
    public RemoteBridgeAgentErrorLedger remoteBridgeAgentErrorLedger(
            @Value("${remote-bridge.agent-error-ledger.max-entries:256}") int maxEntries) {
        return new RemoteBridgeAgentErrorLedger(maxEntries);
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
                                                       RemoteBridgeAuditSink auditSink,
                                                       RemoteBridgeAgentErrorLedger agentErrorLedger,
                                                       DeviceKeyChallengeStore remoteBridgeDeviceKeyChallengeStore,
                                                       TpmDeviceKeySessionEvidenceStore
                                                               remoteBridgeDeviceKeySessionEvidenceStore,
                                                       ViewOnlySessionLifecycle remoteBridgeViewOnlySessionLifecycle) {
        // pin the BEST-EFFORT inbound sink explicitly: slice-3c adds a second RemoteBridgeAuditSink bean (the
        // DURABLE broker recorder) — the control plane's inbound audit must stay the best-effort log sink, so
        // a durable-write failure can never make it ignore a consent denial / kill (the safe outcome proceeds).
        // The device-key stores wire the #548 step-5 consumer; until the step-5b issuance lands no challenge
        // exists to consume, so the path is inert (fail-closed) even though the stores are present.
        BrokerControlPlane controlPlane = new BrokerControlPlane(ledger, store, auditSink, System::currentTimeMillis,
                agentErrorLedger, remoteBridgeDeviceKeyChallengeStore, remoteBridgeDeviceKeySessionEvidenceStore);
        // #1580 — agent-driven terminals (consent-denied / local-abort / indicator-lost) must also terminate the
        // VIEW_ONLY surface, so a stale authorization never keeps fanning frames out after the user pulls consent.
        controlPlane.configureViewOnlyLifecycle(remoteBridgeViewOnlySessionLifecycle);
        return controlPlane;
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
            Environment environment,
            RemoteBridgePermitSigner remoteBridgePermitSigner,
            DurableRemoteBridgeAuditSink remoteBridgeDurableAuditSink,
            @Value("${remote-bridge.broker.policy-version:rb-pilot-v1}") String policyVersion,
            @Value("${remote-bridge.broker.permit-ttl-millis:60000}") long permitTtlMillis,
            @Value("${remote-bridge.broker.enrollment-backed-crypto-identity-pilot-risk-accepted:false}")
                    boolean enrollmentBackedCryptoIdentityPilotRiskAccepted) {
        boolean productionLike = isProductionLike(environment);
        if (enrollmentBackedCryptoIdentityPilotRiskAccepted && productionLike) {
            throw new IllegalStateException("remote-bridge.broker.enrollment-backed-crypto-identity-pilot-risk-accepted "
                    + "is a bounded non-prod pilot exception and is forbidden in a production-like profile");
        }
        RemoteSessionPolicyEngine policyEngine = enrollmentBackedCryptoIdentityPilotRiskAccepted
                ? RemoteSessionPolicyEngine.PILOT_ENROLLMENT_BACKED : RemoteSessionPolicyEngine.PILOT;
        return new RemoteBridgeBroker(true, policyEngine, remoteBridgePermitSigner,
                remoteBridgeDurableAuditSink, policyVersion, permitTtlMillis);
    }

    /**
     * Faz 22.6.1 — approved Remote Response operation catalog. This is server-owned authority metadata; the
     * operator REST endpoint can reference ids from it, but cannot paste or override the command text.
     */
    @Bean
    public RemoteOperationCatalog remoteBridgeOperationCatalog(
            @Value("${remote-bridge.broker.permit-ttl-millis:60000}") long permitTtlMillis) {
        return RemoteOperationCatalog.standard(permitTtlMillis);
    }

    /**
     * Faz 22.6.2 - approved Remote Response script catalog. Operator requests reference immutable
     * scriptId/version/hash metadata from this library; script bodies and command templates are not exposed by
     * the REST response and cannot be supplied by the caller.
     */
    @Bean
    public ApprovedRemoteScriptCatalog remoteBridgeApprovedScriptCatalog(
            @Value("${remote-bridge.broker.permit-ttl-millis:60000}") long permitTtlMillis) {
        return ApprovedRemoteScriptCatalog.standard(permitTtlMillis);
    }

    /**
     * Faz 22.6 D10 slice-2 (+ post-pilot durable, Codex 019ec29a) — the approval-grant store BOTH the approval
     * recorder (write) and the owner-grant gate (read) share. Its type follows {@code owner-grant.gate-type}:
     * {@code APPROVAL_BACKED_DURABLE_DB} → the durable {@link JdbcApprovalGrantStore} (DB-backed, survives restart;
     * probed fail-fast at refresh); otherwise the process-local {@link InMemoryApprovalGrantStore} (the non-prod
     * pilot / unused under DENY_ALL). It stays EMPTY until a dual-control approval is recorded, so by itself it
     * grants nothing.
     */
    @Bean
    public ApprovalGrantStore remoteBridgeApprovalGrantStore(
            JdbcTemplate jdbcTemplate,
            @Value("${ENDPOINT_ADMIN_DB_SCHEMA:endpoint_admin_service}") String schema,
            @Value("${remote-bridge.owner-grant.gate-type:DENY_ALL}") String gateType) {
        if ("APPROVAL_BACKED_DURABLE_DB".equals(gateType == null ? "" : gateType.strip())) {
            JdbcApprovalGrantStore durable = new JdbcApprovalGrantStore(jdbcTemplate, schema);
            // fail-fast: an enabled DURABLE_DB broker whose table is missing refuses to start (not lazily later)
            durable.probeAvailable();
            return durable;
        }
        return new InMemoryApprovalGrantStore();
    }

    /**
     * Faz 22.6 D10 slice-2 — the owner-grant gate, opt-in via {@code remote-bridge.owner-grant.gate-type}.
     * DEFAULT {@code DENY_ALL}: grant nothing (every operation denied for lack of capability) — behaviour
     * unchanged. {@code APPROVAL_BACKED_IN_MEMORY} (non-prod only) reads grants recorded by a dual-control
     * approval; the placeholder in-memory store is forbidden in a prod-like profile.
     */
    @Bean
    public OwnerTokenGate remoteBridgeOwnerTokenGate(
            Environment environment,
            ApprovalGrantStore remoteBridgeApprovalGrantStore,
            @Value("${remote-bridge.owner-grant.gate-type:DENY_ALL}") String gateType) {
        boolean productionLike = isProductionLike(environment);
        return OwnerGrantGateFactory.create(
                OwnerGrantGateFactory.GateType.valueOf(gateType), remoteBridgeApprovalGrantStore, productionLike);
    }

    /**
     * Faz 22.6 D10 slice-3 — the duress source, opt-in via {@code remote-bridge.duress.source-type}. DEFAULT
     * {@code AMBIGUOUS_UNTIL_WIRED}: no real duress producer → AMBIGUOUS → the broker KILLS (fail-closed,
     * behaviour unchanged). {@code PILOT_RISK_ACCEPTED_DISABLED} asserts NONE (the broker does not kill) ONLY
     * with explicit owner risk-acceptance ({@code remote-bridge.duress.pilot-risk-accepted=true}) — the narrow
     * named-roster / attended-only / IT-owned pilot.
     */
    @Bean
    public SessionDuressSignalStore remoteBridgeSessionDuressSignalStore(
            @Value("${remote-bridge.duress.signal-ttl-millis:120000}") long signalTtlMillis) {
        return new SessionDuressSignalStore(signalTtlMillis);
    }

    @Bean
    public TrustEvidenceAssembler.DuressSignalSource remoteBridgeDuressSignalSource(
            Environment environment,
            SessionDuressSignalStore remoteBridgeSessionDuressSignalStore,
            @Value("${remote-bridge.duress.source-type:AMBIGUOUS_UNTIL_WIRED}") String duressSourceType,
            @Value("${remote-bridge.duress.pilot-risk-accepted:false}") boolean duressPilotRiskAccepted) {
        // disabling the human-protection kill is forbidden in a prod-like profile (Codex REVISE) — even with
        // risk-acceptance, a prod deployment must use a real duress source
        boolean productionLike = isProductionLike(environment);
        return DuressSignalSourceFactory.create(
                DuressSignalSourceFactory.SourceType.valueOf(duressSourceType), duressPilotRiskAccepted,
                productionLike, remoteBridgeSessionDuressSignalStore);
    }

    /**
     * Faz 22.6 D10.1 slice-3b — the session device-trust verifier, opt-in via {@code
     * remote-bridge.device-trust.verifier}. DEFAULT {@code FAIL_CLOSED}: device trust never established →
     * {@code deviceTrusted=false} → the broker stays gated (behaviour unchanged). {@code MACHINE_CERT_ENROLLMENT}
     * (non-prod only) trusts a session whose peer is the active ENROLLED machine cert for the tenant/device (via
     * {@link ConnectedDeviceResolver}) — enrollment identity, NOT hardware key attestation. {@code
     * DEVICE_KEY_ATTESTATION} promotes the non-live, replay-prone STATIC CA device-key attestation (#732) carried
     * in the AgentHello envelope — non-prod diagnostics only, NOT #548 closure. {@code
     * REQUIRE_ENROLLMENT_AND_DEVICE_KEY} composes enrollment identity with that CA-static path and is non-prod
     * only: it is REFUSED in a production-like profile until the canonical #548 TPM-native live challenge-response
     * verifier ({@code DEVICE_KEY_ATTESTATION_REAL}, forthcoming) backs the composite's hardware leg.
     */
    @Bean
    public SessionDeviceTrustVerifier remoteBridgeSessionDeviceTrustVerifier(
            Environment environment,
            ConnectedDeviceResolver remoteBridgeConnectedDeviceResolver,
            TpmDeviceKeySessionEvidenceStore remoteBridgeDeviceKeySessionEvidenceStore,
            ObjectProvider<EndpointTpmDeviceBindingRepository> endpointTpmDeviceBindingRepository,
            ObjectProvider<TpmEkChainValidator> tpmEkChainValidator,
            @Value("${remote-bridge.device-trust.verifier:FAIL_CLOSED}") String deviceTrustVerifierType) {
        boolean productionLike = isProductionLike(environment);
        // The REAL TPM-native verifier (DEVICE_KEY_ATTESTATION_REAL / *_REAL) needs the session evidence store,
        // the persisted enrollment-binding repository, and the EK chain validator. The binding repository (JPA)
        // and the EK validator bean (only present when endpoint-admin.tpm-attest.enabled=true) are injected
        // OPTIONALLY: the factory fail-fasts at startup IF a REAL type is selected without them — selecting the
        // strong path without pinned EK roots / the binding store is a misconfiguration, not a silent downgrade.
        // The DEFAULT FAIL_CLOSED verifier (and the other non-REAL types) ignore these deps, so a remote-bridge
        // deployment that does not opt into the REAL verifier needs neither bean.
        DeviceKeyRealVerifierDependencies realDeps = new DeviceKeyRealVerifierDependencies(
                remoteBridgeDeviceKeySessionEvidenceStore, endpointTpmDeviceBindingRepository.getIfAvailable(),
                tpmEkChainValidator.getIfAvailable());
        return SessionDeviceTrustVerifierFactory.create(
                deviceTrustVerifierType, productionLike, remoteBridgeConnectedDeviceResolver, realDeps);
    }

    /**
     * Faz 22.6 T-4a-ii slice-4b — the trust-evidence assembler the operator service feeds the broker. The
     * owner-grant gate (D10 slice-2; default DENY_ALL → grant nothing), the session device-trust verifier (D10.1
     * slice-3b; default FAIL_CLOSED → deviceTrusted false), and the duress source (D10 slice-3; default AMBIGUOUS
     * → KILL) are all injected.
     */
    @Bean
    public TrustEvidenceAssembler remoteBridgeTrustEvidenceAssembler(PeerTrustLedger remoteBridgePeerTrustLedger,
            OwnerTokenGate remoteBridgeOwnerTokenGate,
            SessionDeviceTrustVerifier remoteBridgeSessionDeviceTrustVerifier,
            TrustEvidenceAssembler.DuressSignalSource remoteBridgeDuressSignalSource) {
        return new TrustEvidenceAssembler(remoteBridgePeerTrustLedger, remoteBridgeOwnerTokenGate,
                remoteBridgeSessionDeviceTrustVerifier, remoteBridgeDuressSignalSource);
    }

    /**
     * Faz 22.6 D step-up — the operator step-up verifier, selected fail-closed at construction via the
     * factory's blocking matrix (B1.4c-3 pattern): IN_MEMORY (placeholder) is refused in a prod-like profile,
     * WEBAUTHN requires an operator public key. The verifier is wired here but the operator-facing transport
     * that produces a real WebAuthn assertion and calls {@code session.recordStepUp(...)} is the slice-4c
     * operator endpoint — until then the bean is constructed + config-validated but not yet consumed (same
     * deferred-consumer pattern as the per-peer trust ledger before the control plane). Origin/RP pinning are
     * mandatory for an enabled broker (the verifier ctor refuses a blank origin/RP — fail-closed).
     */
    @Bean
    public OperatorStepUpVerifier remoteBridgeOperatorStepUpVerifier(
            Environment environment,
            @Value("${remote-bridge.step-up.verifier:IN_MEMORY}") String verifierType,
            @Value("${remote-bridge.step-up.in-memory-strength:WEBAUTHN_USER_VERIFICATION}") String inMemoryStrength,
            @Value("${remote-bridge.step-up.public-key-pem:}") String publicKeyPem,
            @Value("${remote-bridge.step-up.signature-algorithm:SHA256withECDSA}") String signatureAlgorithm,
            @Value("${remote-bridge.step-up.expected-origin:}") String expectedOrigin,
            @Value("${remote-bridge.step-up.expected-rp-id:}") String expectedRpId) {
        // a prod-like profile refuses the placeholder IN_MEMORY verifier (same rule as cert-trust/attestation)
        boolean productionLike = isProductionLike(environment);
        return OperatorStepUpVerifierFactory.create(
                OperatorStepUpVerifierFactory.VerifierType.valueOf(verifierType),
                MethodStrength.valueOf(inMemoryStrength),
                publicKeyPem.isBlank() ? null : publicKeyPem,
                signatureAlgorithm,
                expectedOrigin.isBlank() ? null : expectedOrigin,
                expectedRpId.isBlank() ? null : expectedRpId,
                productionLike);
    }

    /**
     * Faz 22.6 D step-up — the operator step-up challenge-response handler: issues a single-use challenge and
     * verifies the operator's WebAuthn assertion against it, recording a VERIFIED step-up into the session. The
     * handler logic is wired here; the operator-facing mTLS transport that AUTHENTICATES the operator and
     * carries the challenge/assertion (an operator gRPC/REST endpoint) is the live slice-4c-transport — until
     * then the bean is constructed but not yet called (deferred-consumer, like the step-up verifier bean).
     */
    @Bean
    public OperatorStepUpHandler remoteBridgeOperatorStepUpHandler(
            OperatorStepUpVerifier remoteBridgeOperatorStepUpVerifier,
            RemoteBridgeSessionStore remoteBridgeSessionStore,
            @Value("${remote-bridge.step-up.expected-origin:}") String expectedOrigin,
            @Value("${remote-bridge.step-up.challenge-ttl-millis:120000}") long challengeTtlMillis) {
        // origin pinning is mandatory for an enabled broker (the handler ctor refuses a blank origin) —
        // fail-closed, the same rule as the verifier bean
        return new OperatorStepUpHandler(remoteBridgeOperatorStepUpVerifier, remoteBridgeSessionStore,
                expectedOrigin.isBlank() ? null : expectedOrigin, challengeTtlMillis);
    }

    /**
     * Faz 22.6 slice-4c-2a — the operator authenticator, selected fail-closed at construction via the
     * factory's blocking matrix (IN_MEMORY placeholder refused in a prod-like profile; the real mTLS/JWT
     * authenticators are the live operator-channel slice). The operator REST transport (slice-4c-2b) extracts
     * the credential ({@link OperatorCredentialExtractor}) and authenticates it through this bean before
     * calling any handler — no verified operator identity, no handler. Constructed here but not yet called
     * until the operator REST controller exists (deferred-consumer).
     */
    @Bean
    public OperatorAuthenticator remoteBridgeOperatorAuthenticator(
            Environment environment,
            @Value("${remote-bridge.operator-auth.type:IN_MEMORY}") String authenticatorType,
            @Value("${remote-bridge.operator-auth.in-memory-token:}") String inMemoryToken,
            @Value("${remote-bridge.operator-auth.in-memory-subject:}") String inMemorySubject,
            @Value("${remote-bridge.operator-auth.in-memory-tenant:}") String inMemoryTenant,
            @Value("${remote-bridge.operator-auth.jwt.jwk-set-uri:}") String jwtJwkSetUri,
            @Value("${remote-bridge.operator-auth.jwt.issuer:}") String jwtIssuer,
            @Value("${remote-bridge.operator-auth.jwt.audience:}") String jwtAudience,
            @Value("${remote-bridge.operator-auth.jwt.tenant-claim:tenant_id}") String jwtTenantClaim,
            @Value("${remote-bridge.operator-auth.jwt.subject-claim:sub}") String jwtSubjectClaim,
            @Value("${remote-bridge.operator-auth.jwt.role-claim-path:realm_access.roles}") String jwtRoleClaimPath,
            @Value("${remote-bridge.operator-auth.jwt.required-operator-role:remote-bridge-operator}")
            String jwtRequiredOperatorRole) {
        // a prod-like profile refuses the placeholder IN_MEMORY authenticator (same rule as the verifiers)
        boolean productionLike = isProductionLike(environment);

        OperatorAuthenticatorFactory.AuthenticatorType type =
                OperatorAuthenticatorFactory.AuthenticatorType.valueOf(authenticatorType);

        // JWT_BEARER (human-to-console): a BRIDGE-specific decoder — signature + temporal + issuer against the
        // IdP JWKS, but WITHOUT the main app's audience validator (the bridge audience is enforced by the
        // authenticator, so a main-app token cannot be replayed here). Built only when JWT is selected + a JWKS
        // URI + issuer are configured; otherwise the factory fail-fasts on the incomplete config.
        OperatorAuthenticatorFactory.JwtBearerConfig jwtConfig = null;
        if (type == OperatorAuthenticatorFactory.AuthenticatorType.JWT_BEARER) {
            JwtDecoder jwtDecoder = null;
            if (!jwtJwkSetUri.isBlank() && !jwtIssuer.isBlank()) {
                NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwtJwkSetUri).build();
                decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(jwtIssuer));
                jwtDecoder = decoder;
            }
            jwtConfig = new OperatorAuthenticatorFactory.JwtBearerConfig(jwtDecoder, jwtIssuer, jwtAudience,
                    jwtTenantClaim, jwtSubjectClaim, jwtRoleClaimPath, jwtRequiredOperatorRole);
        }

        return OperatorAuthenticatorFactory.create(type,
                inMemoryToken.isBlank() ? null : inMemoryToken,
                inMemorySubject.isBlank() ? null : inMemorySubject,
                inMemoryTenant.isBlank() ? null : inMemoryTenant,
                jwtConfig, productionLike);
    }

    /**
     * Faz 22.6 T-4a-ii slice-4b — the operator-side orchestration: drives an OperationRequest through the
     * broker and routes the verdict to the transport (slice-4a primitives). Wired here but the transport
     * endpoint that ACCEPTS operator requests is a later slice — this bean proves the broker↔transport seam
     * composes fail-closed. No authority is minted outside the broker.
     */
    @Bean
    public RemoteBridgeOperatorService remoteBridgeOperatorService(
            RemoteBridgeServerProperties properties,
            ViewOnlySessionLifecycle remoteBridgeViewOnlySessionLifecycle,
            RemoteBridgeSessionStore remoteBridgeSessionStore,
            TrustEvidenceAssembler remoteBridgeTrustEvidenceAssembler,
            RemoteBridgeBroker remoteBridgeBroker,
            ControlStreamRegistry remoteBridgeControlStreamRegistry,
            DurableRemoteBridgeAuditSink remoteBridgeDurableAuditSink,
            SessionDuressSignalStore remoteBridgeSessionDuressSignalStore,
            DeviceKeyChallengeStore remoteBridgeDeviceKeyChallengeStore,
            TpmDeviceKeySessionEvidenceStore remoteBridgeDeviceKeySessionEvidenceStore,
            @Value("${remote-bridge.consent-prompt-ttl-millis:120000}") long consentPromptTtlMillis,
            @Value("${remote-bridge.device-trust.verifier:FAIL_CLOSED}") String deviceTrustVerifierType,
            @Value("${remote-bridge.device-trust.device-key-session.challenge-ttl-millis:180000}")
                    long deviceKeyChallengeTtlMillis) {
        // Faz 22.6 #548 step-5b — derive issuance ON exactly when the canonical TPM-native (REAL) verifier is the
        // active device-trust basis, so there is NO config gap (REAL verifier on ⇒ challenges issued) and non-REAL
        // deployments emit no challenges agents would not answer. The challenge TTL MUST exceed the consent-prompt
        // window plus a margin for the first operation (default 180s > the 120s consent default), else the session
        // evidence would expire before PERMIT-time and the REAL verifier would false-deny (Codex Q3). The evidence
        // + pending-challenge stores let the service clear stale state on terminal + at reused-sessionId open (F1).
        String type = deviceTrustVerifierType == null ? "" : deviceTrustVerifierType.strip();
        boolean deviceKeySessionEnabled = type.equalsIgnoreCase("DEVICE_KEY_ATTESTATION_REAL")
                || type.equalsIgnoreCase("REQUIRE_ENROLLMENT_AND_DEVICE_KEY_REAL");
        RemoteBridgeOperatorService service = new RemoteBridgeOperatorService(remoteBridgeSessionStore,
                remoteBridgeTrustEvidenceAssembler, remoteBridgeBroker, remoteBridgeControlStreamRegistry,
                remoteBridgeDurableAuditSink, System::currentTimeMillis, consentPromptTtlMillis,
                remoteBridgeSessionDuressSignalStore,
                remoteBridgeDeviceKeyChallengeStore, remoteBridgeDeviceKeySessionEvidenceStore,
                deviceKeySessionEnabled, deviceKeyChallengeTtlMillis);
        // #1580 — record a VIEW_ONLY stream authorization on permit push, terminate (revoke + close viewers) on
        // every operator-driven session terminal.
        service.configureViewOnlyStreamAuthorization(remoteBridgeViewOnlySessionLifecycle,
                properties.viewOnly().streamAuthorizationTtlMillis());
        return service;
    }

    @Bean
    public RemoteBridgeNegativeProbeService remoteBridgeNegativeProbeService(
            ControlStreamRegistry remoteBridgeControlStreamRegistry,
            RemoteBridgePermitSigner remoteBridgePermitSigner,
            RemoteBridgeAgentErrorLedger remoteBridgeAgentErrorLedger,
            @Value("${remote-bridge.broker.permit-ttl-millis:60000}") long permitTtlMillis,
            @Value("${remote-bridge.negative-probes.observation-timeout-millis:3000}") long observationTimeoutMillis) {
        return new RemoteBridgeNegativeProbeService(remoteBridgeControlStreamRegistry, remoteBridgePermitSigner,
                remoteBridgeAgentErrorLedger, System::currentTimeMillis, permitTtlMillis, observationTimeoutMillis);
    }

    /**
     * Faz 22.6 slice-4c-2b-2a/2b-2b — the operator-side device→peer resolver: maps an operator's
     * {@code (tenantId, deviceId)} to the connected agent peer (via the device's active cert thumbprint =
     * transportPeerKey), fail-closed at every gate. The operator REST {@code openSession} consumes it to find
     * the verified target before driving the consent flow. Wired here (the bridge gains a read-only dependency
     * on {@link EndpointMachineCertRepository}); disabled-by-default with the rest of the broker.
     */
    @Bean
    public ConnectedDeviceResolver remoteBridgeConnectedDeviceResolver(
            EndpointMachineCertRepository endpointMachineCertRepository,
            ControlStreamRegistry remoteBridgeControlStreamRegistry) {
        return new ConnectedDeviceResolver(endpointMachineCertRepository, remoteBridgeControlStreamRegistry);
    }

    /**
     * Faz 22.6 #1580 (Codex 019f078a) — the VIEW_ONLY fanout registry: the bounded (default 1 viewer/session),
     * latest-wins, control-free broker→operator seam. A subscribed operator viewer (slice-3 web transport)
     * registers here; empty means a frame is simply dropped (no buffer, no persistence).
     */
    @Bean
    public ViewOnlyViewerRegistry remoteBridgeViewOnlyViewerRegistry(RemoteBridgeServerProperties properties) {
        return new ViewOnlyViewerRegistry(properties.viewOnly().maxViewersPerSession());
    }

    /**
     * Faz 22.6 #1580 — the VIEW_ONLY stream authorization gate. The operator service records an authorization
     * here when a VIEW_ONLY SCREEN_VIEW permit is pushed; the data-plane handler reads it as the fail-closed
     * fanout gate. Empty by default → nothing is fanned out.
     */
    @Bean
    public ViewOnlyStreamAuthorizationRegistry remoteBridgeViewOnlyStreamAuthorizationRegistry() {
        return new ViewOnlyStreamAuthorizationRegistry();
    }

    /**
     * Faz 22.6 #1580 (Codex 019f0e78) — the VIEW_ONLY session lifecycle seam shared by the operator service and
     * the broker control plane: authorize a stream on a delivered VIEW_ONLY permit, and TERMINATE (revoke
     * authorization + close viewers) on EVERY terminal — operator (kill/deny/close) AND agent (consent-denied/
     * local-abort/indicator-lost). It wraps the SAME authorization + viewer registry instances the data-plane
     * handler reads, so termination is atomic across both.
     */
    @Bean
    public ViewOnlySessionLifecycle remoteBridgeViewOnlySessionLifecycle(
            ViewOnlyStreamAuthorizationRegistry remoteBridgeViewOnlyStreamAuthorizationRegistry,
            ViewOnlyViewerRegistry remoteBridgeViewOnlyViewerRegistry) {
        return new ViewOnlySessionLifecycle(remoteBridgeViewOnlyStreamAuthorizationRegistry,
                remoteBridgeViewOnlyViewerRegistry);
    }

    /**
     * Faz 22.6 #1580 — the metadata-only VIEW_ONLY audit sink (always on, independent of recording mode). The
     * default logs metadata only (ids + payload SIZE + disposition); it can NEVER carry payload (the interface
     * has no content parameter).
     */
    @Bean
    public ViewOnlyMetadataAuditSink remoteBridgeViewOnlyMetadataAuditSink() {
        return new LoggingViewOnlyMetadataAuditSink();
    }

    /**
     * Faz 22.6 #1580 — the recording-OFF (default) VIEW_ONLY data-plane handler: live fanout + metadata audit,
     * ZERO durable/recording dependency (no content persistence is machine-provable). Wired both as the
     * disabled-mode data plane AND as the fanout leg of the enabled-mode record-then-fanout handler.
     */
    @Bean
    public LiveOnlyViewDataPlaneHandler remoteBridgeLiveOnlyViewDataPlaneHandler(
            RemoteBridgeServerProperties properties,
            ViewOnlyStreamAuthorizationRegistry remoteBridgeViewOnlyStreamAuthorizationRegistry,
            ViewOnlyViewerRegistry remoteBridgeViewOnlyViewerRegistry,
            ViewOnlyMetadataAuditSink remoteBridgeViewOnlyMetadataAuditSink,
            MeterRegistry meterRegistry) {
        return new LiveOnlyViewDataPlaneHandler(remoteBridgeViewOnlyStreamAuthorizationRegistry,
                remoteBridgeViewOnlyViewerRegistry, remoteBridgeViewOnlyMetadataAuditSink, meterRegistry,
                Set.copyOf(properties.viewOnly().allowedFrameContentTypes()), System::currentTimeMillis);
    }

    /**
     * Faz 22.6 #1580 (ADR-0044 D3) — the DATA-plane consumption seam, RECORDING-MODE AWARE:
     * <ul>
     *   <li>{@code recording-mode=disabled} (DEFAULT) → {@link LiveOnlyViewDataPlaneHandler}: live operator
     *       fanout + metadata audit, NO content persistence (no WORM, no content hash).</li>
     *   <li>{@code recording-mode=enabled} → {@link RecordingThenFanoutDataPlaneHandler}: record-BEFORE-fanout to
     *       the durable WORM metadata-hash sink (recording-down → fail-closed), THEN live fanout. The parametric
     *       retention + owner decision reference are validated at config binding — an enabled bridge with neither
     *       refuses to start.</li>
     * </ul>
     * An unknown mode cannot reach here: the enum binding fails closed at startup.
     */
    @Bean
    public DataPlaneHandler remoteBridgeDataPlane(RemoteBridgeServerProperties properties,
                                                 DurableRemoteBridgeAuditSink remoteBridgeDurableAuditSink,
                                                 LiveOnlyViewDataPlaneHandler remoteBridgeLiveOnlyViewDataPlaneHandler) {
        return ViewOnlyDataPlaneFactory.select(properties.viewOnly(), remoteBridgeDurableAuditSink,
                remoteBridgeLiveOnlyViewDataPlaneHandler);
    }

    @Bean
    public RemoteBridgeConnectService remoteBridgeConnectService(RemoteBridgeServerProperties properties,
                                                                 ControlStreamRegistry registry,
                                                                 ScheduledExecutorService heartbeatScheduler,
                                                                 BrokerControlPlane controlPlane,
                                                                 DataPlaneHandler remoteBridgeDataPlane,
                                                                 MeterRegistry meterRegistry) {
        Gauge.builder(BRIDGE_CONTROL_STREAMS_CONNECTED, registry, ControlStreamRegistry::connectedCount)
                .description("Live authenticated remote-bridge CONTROL streams registered by the broker.")
                .register(meterRegistry);
        return new RemoteBridgeConnectService(registry, controlPlane, remoteBridgeDataPlane, meterRegistry,
                heartbeatScheduler, properties.heartbeatIntervalMillis(), properties.maxDataFrameBytes(),
                System::currentTimeMillis, "rb-v1");
    }

    @Bean
    public RemoteBridgeGrpcServer remoteBridgeGrpcServer(RemoteBridgeServerProperties properties,
                                                         RemoteBridgeConnectService service,
                                                         ControlStreamRegistry registry) {
        return new RemoteBridgeGrpcServer(properties, service, registry);
    }

    private static boolean isProductionLike(Environment environment) {
        if (environment == null) {
            return false;
        }
        for (String profile : environment.getActiveProfiles()) {
            String normalized = profile == null ? "" : profile.strip().toLowerCase(Locale.ROOT);
            if ("prod".equals(normalized) || "production".equals(normalized)) {
                return true;
            }
        }
        return false;
    }
}

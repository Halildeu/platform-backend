package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.AttestationVerifier;
import com.example.endpointadmin.remoteaccess.CertTrustEvaluator;
import com.example.endpointadmin.remoteaccess.DeviceIdentityVerifier;
import com.example.endpointadmin.remoteaccess.RemoteAccessVerifierFactory;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeAuditSink;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgePermitSigner;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.BrokerControlPlane;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.PeerEvidenceParser;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.PeerTrustLedger;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeSessionStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

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

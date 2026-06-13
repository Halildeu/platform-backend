package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointMachineCert;
import com.example.endpointadmin.remoteaccess.AttestationVerifier;
import com.example.endpointadmin.remoteaccess.CertThumbprint;
import com.example.endpointadmin.remoteaccess.CertTrustEvaluator;
import com.example.endpointadmin.remoteaccess.DeviceIdentityVerifier;
import com.example.endpointadmin.remoteaccess.DuressResponsePolicy.DuressSignal;
import com.example.endpointadmin.remoteaccess.KeyBasedAttestationVerifier;
import com.example.endpointadmin.remoteaccess.OperatorStepUpVerifier.StepUpAssertion;
import com.example.endpointadmin.remoteaccess.OperatorStepUpVerifier.StepUpChallenge;
import com.example.endpointadmin.remoteaccess.OperatorStepUpVerifier.StepUpVerification;
import com.example.endpointadmin.remoteaccess.RemoteAccessVerifierFactory;
import com.example.endpointadmin.remoteaccess.RemoteOperation;
import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;
import com.example.endpointadmin.remoteaccess.RemoteSessionPolicyEngine;
import com.example.endpointadmin.remoteaccess.WebAuthnStepUpVerifier;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeAuditSink;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeBroker;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeBroker.BrokerOutcome;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeBroker.BrokerOutcome.Kind;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgePermitSigner;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgePermitVerifier;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeSessionStateMachine.State;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.AgentHello;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.OperationRequest;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.TrustEvidenceAssembler.DuressSignalSource;
import com.example.endpointadmin.remoteaccess.bridge.server.ControlStreamRegistry;
import com.example.endpointadmin.remoteaccess.bridge.server.PeerIdentity;
import com.example.endpointadmin.repository.EndpointMachineCertRepository;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Faz 22.6 D10.1 — <b>NON-PROD PERMIT COMPOSITION proof</b> (Codex 019ec29a AGREE, conditional). This is the
 * complement to {@code RemoteBridgeRemoteAccessE2ETest} (which proves the honest DENY when no trust roots exist):
 * now that 3a (transport-bound cert evidence) + 3b (machine-cert enrollment device trust) have landed, this proves
 * the whole trust chain COMPOSES into a real broker {@code PERMIT} — and, by the inverse matrix, that removing ANY
 * single gate flips it back to DENY/KILL (no gate is bypassable).
 *
 * <p><b>The No Fake Work line (Codex):</b> the test MATERIAL is synthetic (a test EC CA + fixture certs, test
 * attestation/WebAuthn/permit signing keys), but NO trust DECISION is faked — every verifier runs its real
 * crypto/path-validation: {@code REAL_PKI} {@link CertTrustEvaluator} over a real cert chain to a test anchor,
 * {@code KEY_BASED} {@link AttestationVerifier} doing real JCA signature verification, a real
 * {@link WebAuthnStepUpVerifier} over a real signed assertion, a real {@link MachineCertEnrollmentDeviceTrustVerifier}
 * over a real {@link ConnectedDeviceResolver}, and the real parser→ledger→assembler→broker→policy→signer chain.
 * Nothing hand-builds {@code RemoteBridgeTrustEvidence(true,true,true)} or mocks a verifier to ALLOW; the only mocks
 * are the DB row ({@link EndpointMachineCertRepository}) and the live-connection lookup ({@link ControlStreamRegistry})
 * — data sources, not trust decisions.
 *
 * <p><b>This proves COMPOSITION, not production readiness.</b> The trust roots here are test fixtures, explicitly
 * prod-forbidden (TRANSPORT_BOUND parser, MACHINE_CERT_ENROLLMENT device trust, REAL_PKI+no-revocation). A green
 * result means "the non-prod PERMIT path composes", NOT "ready" — live/prod still needs D29-EA dual-CA PKI + real
 * CRL/OCSP + real WebAuthn credential enrollment + real provenance anchor + Vault key custody + the attended pilot.
 * It is also NOT a replay-resistant attestation proof (that is slice-3c freshness/replay).
 */
class RemoteBridgeNonProdPermitCompositionE2ETest {

    // a fixed clock INSIDE the committed leaf-valid fixture's validity window (notAfter 2046)
    private static final long NOW = Instant.parse("2035-06-01T00:00:00Z").toEpochMilli();
    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String DEVICE = "22222222-2222-2222-2222-222222222222";
    private static final String OPERATOR = "operator@acik";
    private static final String SESSION_ID = "rb-permit-e2e-1";

    // attestation expected (SLSA provenance) values — the verifier is configured to expect exactly these
    private static final String BUILDER = "trusted-builder@slsa";
    private static final String POLICY = "expected-slsa-policy-hash";
    private static final String DIGEST = "agent-binary-sha256";

    // WebAuthn origin/rp the operator step-up is pinned to
    private static final String ORIGIN = "https://operator.acik.com";
    private static final String RP_ID = "operator.acik.com";
    private static final String ALG = "SHA256withECDSA";

    // synthetic test keys (material is synthetic; the verifiers' decisions are real)
    private final KeyPair attestationKeys = ec();
    private final KeyPair webauthnKeys = ec();
    private final KeyPair permitKeys = ec();

    // the committed PKI fixtures: a real chain (leaf → intermediate → root anchor) the REAL_PKI evaluator validates
    private final X509Certificate leaf = cert("leaf-valid.pem");
    private final X509Certificate intermediate = cert("intermediate-ca.pem");
    private final String leafThumbprint = CertThumbprint.ofDer(encoded(leaf));

    // ---- the wiring knobs (defaults = the all-good PERMIT path; a negative flips exactly one) -----------

    private String trustAnchorFixture = "root-ca.pem"; // the cert-untrusted negative swaps this to a rogue root
    private boolean validAttestation = true;
    private boolean enrolledDevicePresent = true;
    private boolean peerConnected = true;
    private boolean grantRecorded = true;
    private boolean stepUpRecorded = true;
    private long sessionStart = NOW;             // the TTL-stale step-up negative moves the session start back
    private long stepUpAt = NOW;                 // a step-up timestamp at/after sessionStart
    private boolean consentGranted = true;
    private State sessionState = State.ACTIVE;
    private DuressSignal duress = DuressSignal.NONE;
    private boolean auditThrows = false;
    private RemoteOperation operation = RemoteOperation.SCREEN_VIEW;

    /**
     * Wire the FULL real chain with the current knobs and return the broker outcome for one operation. Every
     * verifier is the real one; only the knobs above vary what evidence the real verifiers are given.
     */
    private BrokerOutcome run() {
        long evalNow = NOW + 2_000L;

        // 1) the per-peer trust ledger: REAL_PKI cert evaluator + KEY_BASED attestation verifier + the
        //    TRANSPORT_BOUND parser (3a). The ledger device verifier is deny-all and IGNORED by the assembler
        //    (device trust is the enrollment verifier, 3b) — it only proves the assembler does not read it.
        CertTrustEvaluator certEvaluator = RemoteAccessVerifierFactory.buildTrustEvaluator(
                "REAL_PKI", "DISABLED", fixtureText(trustAnchorFixture), "", true, false, 3_600_000L);
        AttestationVerifier attestationVerifier = RemoteAccessVerifierFactory.buildAttestationVerifierOrDenyAll(
                "KEY_BASED", BUILDER, POLICY, toPem(attestationKeys.getPublic()), ALG, false);
        DeviceIdentityVerifier ledgerDeviceVerifier = new DeviceIdentityVerifier(
                Set.of(), DeviceIdentityVerifier.DeviceProtectionLevel.SECURE_ELEMENT_OR_TPM);
        PeerTrustLedger ledger = new PeerTrustLedger(certEvaluator, attestationVerifier, ledgerDeviceVerifier,
                new TransportBoundPeerEvidenceParser(), 3_600_000L);

        // 2) the agent peer (transport leaf) + record its hello → the real verifiers run in the ledger
        PeerIdentity peer = new PeerIdentity(leafThumbprint, Optional.of(DEVICE), List.of(leaf, intermediate));
        AgentHello hello = new AgentHello("0.2.3", DEVICE, leafThumbprint,
                attestationEvidenceB64(validAttestation), "rb-v1", Set.of(RemoteSessionCapability.VIEW_ONLY));
        ledger.record(peer, hello, NOW);

        // 3) device trust = the real enrollment verifier (3b) over a real resolver. The DB row (machine cert) and
        //    the live-connection lookup are mocked DATA SOURCES — the resolver still runs every fail-closed gate.
        // build the fixture row BEFORE stubbing the repo (its own when(...) calls must complete first — Mockito
        // forbids nested stubbing)
        Optional<EndpointMachineCert> enrolledRow =
                enrolledDevicePresent ? Optional.of(enrolledFixtureCert()) : Optional.empty();
        EndpointMachineCertRepository certRepo = mock(EndpointMachineCertRepository.class);
        when(certRepo.findActiveByTenantIdAndDeviceId(UUID.fromString(TENANT), UUID.fromString(DEVICE)))
                .thenReturn(enrolledRow);
        ControlStreamRegistry registry = mock(ControlStreamRegistry.class);
        // narrow: the registry lookup is keyed by the enrolled cert's thumbprint (= the transport leaf) — so the
        // DB-machine-cert → live-peer binding is preserved in the composition, not waved through with any()
        when(registry.connectedPeer(leafThumbprint))
                .thenReturn(peerConnected ? Optional.of(peer) : Optional.empty());
        ConnectedDeviceResolver resolver = new ConnectedDeviceResolver(certRepo, registry);
        SessionDeviceTrustVerifier deviceTrustVerifier = new MachineCertEnrollmentDeviceTrustVerifier(resolver);

        // 4) owner grant = the real approval-backed gate over a real grant store
        ApprovalGrantStore grantStore = new InMemoryApprovalGrantStore();
        if (grantRecorded) {
            // the grant key is pinned to the session incarnation (sessionStart) — it must match the live session
            grantStore.record(new ApprovalGrantStore.ApprovalGrantKey(SESSION_ID, TENANT, OPERATOR, sessionStart),
                    Set.of(RemoteSessionCapability.VIEW_ONLY), NOW + 600_000L);
        }
        OwnerTokenGate ownerGate = OwnerGrantGateFactory.create(
                OwnerGrantGateFactory.GateType.APPROVAL_BACKED_IN_MEMORY, grantStore, false);

        // 5) the assembler (real ledger + enrollment device verifier + owner gate + a duress source)
        DuressSignalSource duressSource = (sid, now) -> duress;
        TrustEvidenceAssembler assembler =
                new TrustEvidenceAssembler(ledger, ownerGate, deviceTrustVerifier, duressSource);

        // 6) the live session: ACTIVE + an attended consent lease + a real recorded WebAuthn step-up
        RemoteBridgeSession session = new RemoteBridgeSession(SESSION_ID, leafThumbprint, DEVICE, OPERATOR, TENANT,
                "Operator X", Set.of(RemoteSessionCapability.VIEW_ONLY), NOW + 600_000L, sessionStart, sessionState);
        if (consentGranted) {
            session.grantConsent(true, NOW + 600_000L);
        }
        if (stepUpRecorded) {
            session.recordStepUp(realWebAuthnStepUp(stepUpAt));
        }

        // 7) assemble the evidence from the REAL chain — never hand-built
        var evidence = assembler.assemble(session, evalNow);

        // 8) the broker: PILOT engine + a real permit signer + a (real) audit sink that commits (or throws)
        RemoteBridgePermitSigner signer = new RemoteBridgePermitSigner(
                permitKeys.getPrivate(), "rb-permit-kid-1", RemoteBridgePermitSigner.PERMIT_ALG);
        RemoteBridgeAuditSink auditSink = auditThrows
                ? event -> { throw new IllegalStateException("durable record failed"); }
                : event -> { };
        RemoteBridgeBroker broker = new RemoteBridgeBroker(true, RemoteSessionPolicyEngine.PILOT, signer,
                auditSink, "rb-pilot-v1", 60_000L);

        String commandLine = operation == RemoteOperation.PTY_COMMAND ? "whoami" : null;
        return broker.handle(new OperationRequest(SESSION_ID, "op-1", operation, commandLine),
                evidence, sessionState, 0L, evalNow);
    }

    // ============================ the POSITIVE proof ============================

    @Test
    void aRealPermitIsIssuedWhenEveryGateIsSatisfiedByRealEvidence() {
        BrokerOutcome outcome = run();

        assertEquals(Kind.PERMIT, outcome.kind(),
                "the full real trust chain composes into a broker PERMIT (non-prod composition)");
        assertNotNull(outcome.permit(), "a signed operation permit is issued");
        assertEquals("rb-permit-kid-1", outcome.permit().kid());
        assertEquals(RemoteSessionCapability.VIEW_ONLY, outcome.permit().capability());
        assertEquals(SESSION_ID, outcome.permit().sessionId());
        // the permit's signature genuinely verifies under the signer key (it is a real signed permit, not a stub)
        assertTrue(new RemoteBridgePermitVerifier(permitKeys.getPublic(), "rb-permit-kid-1")
                .verify(outcome.permit(), NOW + 2_000L), "the issued permit signature verifies");
    }

    // ============================ the INVERSE matrix (no gate is bypassable) ============================
    // Each removes exactly ONE gate, leaving every other gate satisfied, and asserts the honest outcome. The
    // cert gate is exercised here too via a rogue trust anchor (keeping the chain/thumbprint/enrollment intact),
    // so every CRYPTO_IDENTITY input (cert / attestation / device) is proven non-bypassable in the composition.

    @Test
    void aTamperedAttestationDeniesAtCryptoIdentity() {
        validAttestation = false;
        BrokerOutcome outcome = run();
        assertEquals(Kind.DENY, outcome.kind());
        assertEquals("policy:CRYPTO_IDENTITY", outcome.reason());
    }

    @Test
    void noEnrolledDeviceDeniesAtCryptoIdentity() {
        enrolledDevicePresent = false;
        BrokerOutcome outcome = run();
        assertEquals(Kind.DENY, outcome.kind());
        assertEquals("policy:CRYPTO_IDENTITY", outcome.reason(), "deviceTrusted false → CRYPTO_IDENTITY");
    }

    @Test
    void aDisconnectedPeerDeniesAtCryptoIdentity() {
        peerConnected = false;
        BrokerOutcome outcome = run();
        assertEquals(Kind.DENY, outcome.kind());
        assertEquals("policy:CRYPTO_IDENTITY", outcome.reason(), "no live enrolled peer → deviceTrusted false");
    }

    @Test
    void anUntrustedCertDeniesAtCryptoIdentity() {
        // the SAME real chain + thumbprint + enrollment + connected peer — only the trust anchor is a rogue root
        // the chain does NOT build to. The real REAL_PKI CertPathTrustEvaluator returns NOT_TRUSTED → certTrusted
        // false → CRYPTO_IDENTITY. This proves the cert gate is not bypassable inside the composition.
        trustAnchorFixture = "unknown-root.pem";
        BrokerOutcome outcome = run();
        assertEquals(Kind.DENY, outcome.kind());
        assertEquals("policy:CRYPTO_IDENTITY", outcome.reason(), "a chain that does not build to the anchor → untrusted");
    }

    @Test
    void noFreshStepUpDeniesAtStepUp() {
        stepUpRecorded = false;
        BrokerOutcome outcome = run();
        assertEquals(Kind.DENY, outcome.kind());
        assertEquals("policy:STEP_UP", outcome.reason());
    }

    @Test
    void aStaleStepUpDeniesAtStepUp() {
        // a GENUINE TTL-stale step-up: timestamped AFTER session start (not a pre-session replay) but older than
        // the SCREEN_VIEW 30-min freshness TTL at eval time → STEP_UP (move sessionStart back so the step-up is
        // recorded, then let it age past the TTL)
        sessionStart = NOW - (45 * 60_000L);
        stepUpAt = NOW - (40 * 60_000L); // >= sessionStart (recorded), but 40 min before eval (TTL 30 min) → stale
        BrokerOutcome outcome = run();
        assertEquals(Kind.DENY, outcome.kind());
        assertEquals("policy:STEP_UP", outcome.reason());
    }

    @Test
    void aPreSessionStepUpDeniesAtStepUp() {
        // a step-up timestamped BEFORE session start is a forged/replayed credential — recordStepUp drops it
        // (monotonic guard), so the session has no fresh step-up → STEP_UP
        stepUpAt = NOW - (10 * 60_000L); // before sessionStart (= NOW)
        BrokerOutcome outcome = run();
        assertEquals(Kind.DENY, outcome.kind());
        assertEquals("policy:STEP_UP", outcome.reason());
    }

    @Test
    void noOwnerGrantDeniesAtOperation() {
        grantRecorded = false;
        BrokerOutcome outcome = run();
        assertEquals(Kind.DENY, outcome.kind());
        assertEquals("policy:OPERATION", outcome.reason(), "no granted capability → OPERATION gate");
    }

    @Test
    void aDuressSignalKillsTheSession() {
        duress = DuressSignal.AMBIGUOUS; // the unwired/ambiguous default is itself fail-closed → KILL
        BrokerOutcome outcome = run();
        assertEquals(Kind.KILL, outcome.kind());
        assertNotNull(outcome.kill());
    }

    @Test
    void aNonActiveSessionDeniesBeforePolicy() {
        sessionState = State.CONSENT_PENDING;
        BrokerOutcome outcome = run();
        assertEquals(Kind.DENY, outcome.kind());
        assertEquals("session-not-active", outcome.reason());
    }

    @Test
    void noConsentLeaseDeniesBeforePolicy() {
        consentGranted = false;
        BrokerOutcome outcome = run();
        assertEquals(Kind.DENY, outcome.kind());
        assertEquals("no-active-consent-lease", outcome.reason());
    }

    @Test
    void aFailedDurableRecordDeniesWithoutAPermit() {
        auditThrows = true;
        BrokerOutcome outcome = run();
        assertEquals(Kind.DENY, outcome.kind());
        assertEquals("recording-failed", outcome.reason(), "no permit is issued without a durable record");
    }

    // ============================ helpers (synthetic material, real decisions) ============================

    private static KeyPair ec() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static X509Certificate cert(String fixture) {
        try (InputStream in = RemoteBridgeNonProdPermitCompositionE2ETest.class
                .getResourceAsStream("/remoteaccess/pki/" + fixture)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + fixture);
            }
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        } catch (Exception e) {
            throw new IllegalStateException("read cert " + fixture, e);
        }
    }

    private static String fixtureText(String fixture) {
        try (InputStream in = RemoteBridgeNonProdPermitCompositionE2ETest.class
                .getResourceAsStream("/remoteaccess/pki/" + fixture)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + fixture);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("read text " + fixture, e);
        }
    }

    private static byte[] encoded(X509Certificate cert) {
        try {
            return cert.getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String toPem(PublicKey key) {
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(key.getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + b64 + "\n-----END PUBLIC KEY-----\n";
    }

    /**
     * The agent's attestation evidence: base64 of "binaryDigest|builderId|slsaPredicateHash|predicateSignature"
     * (the 4 SLSA fields the parser splits on '|'). The signature is a REAL ECDSA signature over the verifier's
     * length-prefixed canonical provenance — when {@code valid} is false the signature is over a different digest,
     * so the real {@link KeyBasedAttestationVerifier} genuinely rejects it.
     */
    private String attestationEvidenceB64(boolean valid) {
        try {
            String signedDigest = valid ? DIGEST : "tampered-digest";
            byte[] provenance = KeyBasedAttestationVerifier.canonicalProvenance(signedDigest, BUILDER, POLICY);
            Signature signer = Signature.getInstance(ALG);
            signer.initSign(attestationKeys.getPrivate());
            signer.update(provenance);
            String sig = Base64.getEncoder().encodeToString(signer.sign());
            // the EVIDENCE always advertises the expected DIGEST; only the signed bytes differ when invalid, so a
            // tampered attestation has a signature that does not verify over the advertised provenance
            String evidence = DIGEST + "|" + BUILDER + "|" + POLICY + "|" + sig;
            return Base64.getEncoder().encodeToString(evidence.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A real, signed WebAuthn USER_PRESENCE assertion verified by a real verifier → a VERIFIED step-up. */
    private StepUpVerification realWebAuthnStepUp(long at) {
        byte[] rawChallenge = "rb-permit-e2e-challenge".getBytes(StandardCharsets.UTF_8);
        String challengeB64 = Base64.getEncoder().encodeToString(rawChallenge);
        String challengeB64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(rawChallenge);
        StepUpChallenge challenge = new StepUpChallenge(challengeB64, ORIGIN, at);
        String clientDataJson = "{\"type\":\"webauthn.get\",\"challenge\":\"" + challengeB64Url
                + "\",\"origin\":\"" + ORIGIN + "\"}";
        StepUpAssertion assertion = signAssertion(clientDataJson, 0x01); // 0x01 = USER_PRESENT (UP)
        StepUpVerification verification = new WebAuthnStepUpVerifier(
                webauthnKeys.getPublic(), ALG, ORIGIN, RP_ID).verify(challenge, assertion, at);
        if (!verification.isVerified()) {
            throw new IllegalStateException("test WebAuthn assertion did not verify");
        }
        return verification;
    }

    private StepUpAssertion signAssertion(String clientDataJson, int flags) {
        try {
            byte[] rpIdHash = MessageDigest.getInstance("SHA-256").digest(RP_ID.getBytes(StandardCharsets.UTF_8));
            byte[] authData = new byte[37];
            System.arraycopy(rpIdHash, 0, authData, 0, 32);
            authData[32] = (byte) flags;
            byte[] clientDataBytes = clientDataJson.getBytes(StandardCharsets.UTF_8);
            byte[] clientDataHash = MessageDigest.getInstance("SHA-256").digest(clientDataBytes);
            byte[] signed = new byte[authData.length + clientDataHash.length];
            System.arraycopy(authData, 0, signed, 0, authData.length);
            System.arraycopy(clientDataHash, 0, signed, authData.length, clientDataHash.length);
            Signature signer = Signature.getInstance(ALG);
            signer.initSign(webauthnKeys.getPrivate());
            signer.update(signed);
            return new StepUpAssertion(
                    Base64.getEncoder().encodeToString(clientDataBytes),
                    Base64.getEncoder().encodeToString(authData),
                    Base64.getEncoder().encodeToString(signer.sign()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A fixture machine cert that is active, in-window at NOW, for an ONLINE device in the tenant, whose
     *  thumbprint IS the transport leaf — i.e. a genuinely enrolled device (mocked DB row, not a faked trust). */
    private EndpointMachineCert enrolledFixtureCert() {
        EndpointDevice device = mock(EndpointDevice.class);
        when(device.getStatus()).thenReturn(DeviceStatus.ONLINE);
        when(device.getTenantId()).thenReturn(UUID.fromString(TENANT));
        EndpointMachineCert cert = mock(EndpointMachineCert.class);
        when(cert.getCertThumbprint()).thenReturn(leafThumbprint);
        when(cert.getCertNotBefore()).thenReturn(Instant.ofEpochMilli(NOW - 86_400_000L));
        when(cert.getCertNotAfter()).thenReturn(Instant.ofEpochMilli(NOW + 86_400_000L));
        when(cert.getDevice()).thenReturn(device);
        return cert;
    }
}

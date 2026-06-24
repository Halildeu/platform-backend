package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.model.EndpointTpmDeviceBinding;
import com.example.endpointadmin.remoteaccess.CertThumbprint;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeSessionStateMachine.State;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.DeviceKeyChallenge;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.SessionDeviceTrustVerifier.Basis;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.SessionDeviceTrustVerifier.DeviceTrustDecision;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.TpmDeviceKeySessionEvidenceStore.StoredEvidence;
import com.example.endpointadmin.remoteaccess.bridge.server.PeerIdentity;
import com.example.endpointadmin.repository.EndpointTpmDeviceBindingRepository;
import com.example.endpointadmin.tpmattest.TpmEkChainValidator;
import com.example.endpointadmin.tpmattest.TpmPublicArea;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Faz 22.6 #548 slice-1 step-5 — the canonical {@link DeviceKeyAttestationRealSessionDeviceTrustVerifier}: a
 * synthetic-but-self-consistent software-TPM fixture proves the POSITIVE path returns a genuine
 * {@link Basis#HARDWARE_KEY_ATTESTATION}, and a NEGATIVE MATRIX proves every gate fails closed.
 *
 * <p>The fixture marshals real {@code TPMT_PUBLIC} (AK + device key), {@code TPMS_ATTEST} (certify + quote) and
 * {@code TPMT_SIGNATURE} (AK over the attests, device key over the binding context) from JCA software keys — so the
 * AK certify, the AK quote over the broker nonce, and the device-key signature over the canonical binding context
 * all verify for real. The EK chain reuses the swtpm golden EK cert + its root (a real PKIX chain); the live mTLS
 * leaf is a mock X509 carrying the device public key (so the device-key&harr;leaf SPKI equality is exercised). The
 * persisted enrollment binding is set to the fixture's own AK/EK/device digests.
 */
class DeviceKeyAttestationRealSessionDeviceTrustVerifierTest {

    private static final long NOW = 1_900_000_000_000L;
    private static final long TTL = 60_000L;
    private static final String PEER_KEY = "ab".repeat(32); // 64-hex transport peer key (cert DER sha256 shape)
    private static final String CHALLENGE_ID = "00112233445566778899aabbccddeeff";
    private static final String SESSION_ID = "sess-" + CHALLENGE_ID;
    private static final String PROTOCOL = DeviceKeyChallengeStore.PROTOCOL_VERSION;

    private static X509Certificate goldenEkCert;
    private static TpmEkChainValidator ekChainValidator;
    private static Fixture fx;

    private TpmDeviceKeySessionEvidenceStore evidenceStore;
    private ConnectedDeviceResolver resolver;
    private EndpointTpmDeviceBindingRepository bindings;
    private DeviceKeyAttestationRealSessionDeviceTrustVerifier verifier;

    private final UUID tenant = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID device = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private RemoteBridgeSession session;
    private X509Certificate mockLeaf;

    @BeforeAll
    static void buildFixtureAndEkChain() throws Exception {
        JsonNode g;
        try (var in = DeviceKeyAttestationRealSessionDeviceTrustVerifierTest.class
                .getResourceAsStream("/tpmattest/golden-rsa.json")) {
            g = new ObjectMapper().readTree(in);
        }
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        goldenEkCert = (X509Certificate) cf.generateCertificate(
                new java.io.ByteArrayInputStream(Base64.getDecoder().decode(g.get("ekCertDer").asText())));
        X509Certificate caCert = (X509Certificate) cf.generateCertificate(
                new java.io.ByteArrayInputStream(Base64.getDecoder().decode(g.get("caCertDer").asText())));
        ekChainValidator = new TpmEkChainValidator(Set.of(sha256Hex(caCert.getEncoded())), List.of(caCert));
        fx = Fixture.build();
    }

    @BeforeEach
    void wire() {
        evidenceStore = new TpmDeviceKeySessionEvidenceStore();
        resolver = mock(ConnectedDeviceResolver.class);
        bindings = mock(EndpointTpmDeviceBindingRepository.class);
        verifier = new DeviceKeyAttestationRealSessionDeviceTrustVerifier(
                evidenceStore, resolver, bindings, ekChainValidator);

        session = new RemoteBridgeSession(SESSION_ID, PEER_KEY, device.toString(),
                "operator@example.com", tenant.toString(), "Operator", Set.of(), NOW + TTL, NOW,
                State.CONSENT_PENDING);
        // bind THIS session incarnation to the challenge the fixture's evidence answers (openSession does this live)
        session.bindDeviceKeyChallenge(CHALLENGE_ID);

        mockLeaf = mock(X509Certificate.class);
        when(mockLeaf.getPublicKey()).thenReturn(fx.devicePub);
        PeerIdentity resolvedPeer = new PeerIdentity(PEER_KEY, Optional.empty(), List.of(mockLeaf));
        when(resolver.resolveConnectedPeer(eq(tenant), eq(device), any(Instant.class)))
                .thenReturn(Optional.of(resolvedPeer));
        when(bindings.findByTenantIdAndDeviceIdAndRevokedAtIsNull(tenant, device))
                .thenReturn(Optional.of(fx.binding(tenant, device)));
    }

    // ───────────────────────────── positive ─────────────────────────────

    @Test
    void validLiveAttestation_yieldsHardwareKeyAttestation() {
        storeFreshEvidence(fx.attestation());
        DeviceTrustDecision decision = verifier.verify(session, null, NOW);
        assertThat(decision.trusted()).as("a fully valid TPM-native challenge-response is hardware-attested").isTrue();
        assertThat(decision.basis()).isEqualTo(Basis.HARDWARE_KEY_ATTESTATION);
    }

    // ───────────────────────────── early gates ─────────────────────────────

    @Test
    void missingSession_denies() {
        assertDeny(verifier.verify(null, null, NOW), "missing-session");
    }

    @Test
    void noEvidence_denies() {
        // nothing stored for this (sessionId, peerKey)
        assertDeny(verifier.verify(session, null, NOW), "no-fresh-device-key-evidence");
    }

    @Test
    void expiredEvidence_denies() {
        storeFreshEvidence(fx.attestation());
        assertDeny(verifier.verify(session, null, NOW + TTL + 1), "no-fresh-device-key-evidence");
    }

    @Test
    void bindingContextMismatch_denies() {
        // the agent signed/claimed a DIFFERENT context than the broker challenge would re-derive
        TpmDeviceKeySessionAttestation tampered = fx.attestationWithBindingContext("not-the-broker-context".getBytes());
        storeFreshEvidence(tampered);
        assertDeny(verifier.verify(session, null, NOW), "binding-context-mismatch");
    }

    @Test
    void tamperedDeviceKeySignature_denies() {
        byte[] badSig = fx.deviceKeySig.clone();
        badSig[badSig.length - 1] ^= 0x01; // corrupt the device-key signature → live-possession proof fails
        storeFreshEvidence(fx.attestationWithDeviceKeySig(badSig));
        assertDeny(verifier.verify(session, null, NOW), "device-key-attestation-error");
    }

    // ───────────────────────────── version + session binding (Codex F1/F2) ─────────────────────────────

    @Test
    void wrongChallengeProtocol_denies() {
        storeFreshEvidence(fx.attestation(), "bogus-protocol-v9");
        assertDeny(verifier.verify(session, null, NOW), "challenge-protocol-mismatch");
    }

    @Test
    void wrongResponseSchema_denies() {
        storeFreshEvidence(fx.attestationWithSchema("faz22.6.device-key-session.v999"));
        assertDeny(verifier.verify(session, null, NOW), "response-schema-mismatch");
    }

    @Test
    void bindingContextForADifferentSession_denies() {
        // a context (and signature) minted for ANOTHER session must not verify for THIS session — the verifier
        // recomputes with session.sessionId(), so a different-session context no longer matches (Codex F1)
        byte[] otherSessionContext = DeviceKeySessionBindingContext.compute(
                "sess-some-other-session", CHALLENGE_ID, fx.nonce, PEER_KEY, NOW + TTL);
        storeFreshEvidence(fx.attestationWithBindingContext(otherSessionContext));
        assertDeny(verifier.verify(session, null, NOW), "binding-context-mismatch");
    }

    @Test
    void evidenceForAPriorChallengeIncarnationOfAReusedSessionId_denies() {
        // the (sessionId, peer) slot holds evidence for the fixture's CHALLENGE_ID, but THIS session incarnation
        // now expects a DIFFERENT challenge (a reused sessionId re-issued) — a stale in-flight writer that raced
        // past eviction cannot mint authority because its challengeId no longer matches the session's current one
        session.bindDeviceKeyChallenge("ffffffffffffffffffffffffffffffff");
        storeFreshEvidence(fx.attestation());
        assertDeny(verifier.verify(session, null, NOW), "device-key-challenge-incarnation-mismatch");
    }

    @Test
    void decoupledEvidenceExpiry_denies() {
        // defense-in-depth (Codex): a StoredEvidence whose freshness window is LONGER than the challenge's own
        // window passes consumeFresh(now) but the verifier rejects it — evidence can never outlive the broker nonce
        DeviceKeyChallenge challenge = new DeviceKeyChallenge(CHALLENGE_ID,
                Base64.getEncoder().encodeToString(fx.nonce), NOW, NOW + TTL, PEER_KEY, PROTOCOL);
        evidenceStore.store(session.sessionId(), PEER_KEY,
                new StoredEvidence(challenge, fx.attestation(), NOW, NOW + 10 * TTL)); // decoupled (longer) expiry
        assertDeny(verifier.verify(session, null, NOW), "device-key-evidence-window-invalid");
    }

    // ───────────────────────────── transport / leaf binding ─────────────────────────────

    @Test
    void noConnectedEnrolledPeer_denies() {
        when(resolver.resolveConnectedPeer(eq(tenant), eq(device), any(Instant.class))).thenReturn(Optional.empty());
        storeFreshEvidence(fx.attestation());
        assertDeny(verifier.verify(session, null, NOW), "no-active-enrolled-connected-peer");
    }

    @Test
    void resolvedPeerIsADifferentTransport_denies() {
        PeerIdentity other = new PeerIdentity("cd".repeat(32), Optional.empty(), List.of(mockLeaf));
        when(resolver.resolveConnectedPeer(eq(tenant), eq(device), any(Instant.class)))
                .thenReturn(Optional.of(other));
        storeFreshEvidence(fx.attestation());
        assertDeny(verifier.verify(session, null, NOW), "transport-peer-mismatch");
    }

    @Test
    void liveLeafKeyIsNotTheAttestedDeviceKey_denies() throws Exception {
        // a different leaf public key → attested device-key SPKI != live mTLS leaf SPKI
        X509Certificate otherLeaf = mock(X509Certificate.class);
        when(otherLeaf.getPublicKey()).thenReturn(rsa3072().getPublic());
        when(resolver.resolveConnectedPeer(eq(tenant), eq(device), any(Instant.class)))
                .thenReturn(Optional.of(new PeerIdentity(PEER_KEY, Optional.empty(), List.of(otherLeaf))));
        storeFreshEvidence(fx.attestation());
        assertDeny(verifier.verify(session, null, NOW), "device-key-leaf-binding-mismatch");
    }

    // ───────────────────────────── persisted enrollment binding ─────────────────────────────

    @Test
    void noPersistedBinding_denies() {
        when(bindings.findByTenantIdAndDeviceIdAndRevokedAtIsNull(tenant, device)).thenReturn(Optional.empty());
        storeFreshEvidence(fx.attestation());
        assertDeny(verifier.verify(session, null, NOW), "no-active-tpm-binding");
    }

    @Test
    void persistedDeviceKeyDiffersFromLiveAndAttested_denies() {
        // a binding whose device-key SPKI is some other value → triple-equality breaks (a stale/foreign binding)
        EndpointTpmDeviceBinding wrong = new EndpointTpmDeviceBinding(tenant, device, UUID.randomUUID(),
                fx.akName, sha256Hex(fx.akTpm2b), sha256Hex(goldenEkCertDer()), "deadbeef".repeat(8),
                Instant.ofEpochMilli(NOW), Instant.ofEpochMilli(NOW));
        when(bindings.findByTenantIdAndDeviceIdAndRevokedAtIsNull(tenant, device)).thenReturn(Optional.of(wrong));
        storeFreshEvidence(fx.attestation());
        assertDeny(verifier.verify(session, null, NOW), "device-key-leaf-binding-mismatch");
    }

    @Test
    void persistedAkNameDiffers_denies() {
        EndpointTpmDeviceBinding wrong = new EndpointTpmDeviceBinding(tenant, device, UUID.randomUUID(),
                "00".repeat(34).getBytes(), sha256Hex(fx.akTpm2b), sha256Hex(goldenEkCertDer()),
                sha256Hex(fx.devicePub.getEncoded()), Instant.ofEpochMilli(NOW), Instant.ofEpochMilli(NOW));
        when(bindings.findByTenantIdAndDeviceIdAndRevokedAtIsNull(tenant, device)).thenReturn(Optional.of(wrong));
        storeFreshEvidence(fx.attestation());
        assertDeny(verifier.verify(session, null, NOW), "ak-name-mismatch");
    }

    @Test
    void persistedEkCertDiffers_denies() {
        EndpointTpmDeviceBinding wrong = new EndpointTpmDeviceBinding(tenant, device, UUID.randomUUID(),
                fx.akName, sha256Hex(fx.akTpm2b), "feed".repeat(16), sha256Hex(fx.devicePub.getEncoded()),
                Instant.ofEpochMilli(NOW), Instant.ofEpochMilli(NOW));
        when(bindings.findByTenantIdAndDeviceIdAndRevokedAtIsNull(tenant, device)).thenReturn(Optional.of(wrong));
        storeFreshEvidence(fx.attestation());
        assertDeny(verifier.verify(session, null, NOW), "ek-cert-mismatch");
    }

    // ───────────────────────────── EK chain / certify / quote ─────────────────────────────

    @Test
    void missingEkCert_denies() {
        storeFreshEvidence(fx.attestationWithoutEkCert());
        assertDeny(verifier.verify(session, null, NOW), "ek-cert-required");
    }

    @Test
    void ekCertDoesNotChainToPinnedRoot_denies() throws Exception {
        // a validator that PINS the EK LEAF itself as the only anchor: the ek-cert-sha256 match still passes (the
        // persisted digest is the same golden EK), but PKIX cannot validate the leaf against itself (it is signed
        // by its CA, not self-signed) → ek-chain-untrusted. Proves the chain step is load-bearing, not just the hash.
        TpmEkChainValidator leafOnlyValidator = new TpmEkChainValidator(
                Set.of(sha256Hex(goldenEkCertDer())), List.of(goldenEkCert));
        DeviceKeyAttestationRealSessionDeviceTrustVerifier v = new DeviceKeyAttestationRealSessionDeviceTrustVerifier(
                evidenceStore, resolver, bindings, leafOnlyValidator);
        storeFreshEvidence(fx.attestation());
        assertDeny(v.verify(session, null, NOW), "ek-chain-untrusted");
    }

    @Test
    void tamperedCertify_denies() {
        byte[] badCertifySig = fx.certifySig.clone();
        badCertifySig[badCertifySig.length - 1] ^= 0x01;
        storeFreshEvidence(fx.attestationWithCertifySig(badCertifySig));
        assertDeny(verifier.verify(session, null, NOW), "device-key-attestation-error");
    }

    @Test
    void quoteOverWrongNonce_denies() {
        // a quote signed over a DIFFERENT nonce than this challenge's → replay/stale-nonce
        storeFreshEvidence(fx.attestationWithQuoteNonce("a-different-nonce-32-bytes-long!!".getBytes()));
        assertDeny(verifier.verify(session, null, NOW), "device-key-attestation-error");
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private void storeFreshEvidence(TpmDeviceKeySessionAttestation attestation) {
        storeFreshEvidence(attestation, PROTOCOL);
    }

    private void storeFreshEvidence(TpmDeviceKeySessionAttestation attestation, String protocolVersion) {
        DeviceKeyChallenge challenge = new DeviceKeyChallenge(CHALLENGE_ID,
                Base64.getEncoder().encodeToString(fx.nonce), NOW, NOW + TTL, PEER_KEY, protocolVersion);
        evidenceStore.store(session.sessionId(), PEER_KEY,
                new StoredEvidence(challenge, attestation, NOW, NOW + TTL));
    }

    private static void assertDeny(DeviceTrustDecision decision, String expectedReason) {
        assertThat(decision.trusted()).as("must fail closed").isFalse();
        assertThat(decision.basis()).isEqualTo(Basis.NONE);
        assertThat(decision.reason()).isEqualTo(expectedReason);
    }

    private static byte[] goldenEkCertDer() {
        try {
            return goldenEkCert.getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair rsa3072() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(3072);
        return kpg.generateKeyPair();
    }

    private static String sha256Hex(byte[] der) {
        return CertThumbprint.ofDer(der);
    }

    /**
     * A self-consistent software-TPM fixture: AK + device RSA-3072 keys, their marshaled {@code TPMT_PUBLIC}, the
     * AK-signed certify (of the device key) + quote (over the nonce), and the device-key-signed binding context.
     * Every structure is parse-checked on build so a marshaling bug surfaces HERE, not as a confusing deny.
     */
    private static final class Fixture {
        final byte[] nonce = "broker-nonce-32-bytes-exactly!!!".getBytes(StandardCharsets.US_ASCII);
        PublicKey devicePub;
        PrivateKey akPrivate;
        byte[] akTpm2b;
        byte[] deviceTpm2b;
        byte[] akName;
        byte[] certifyInfo;
        byte[] certifySig;
        byte[] quote;
        byte[] quoteSig;
        byte[] bindingContext;
        byte[] deviceKeySig;

        static Fixture build() throws Exception {
            Fixture f = new Fixture();
            KeyPair akKp = rsa3072();
            KeyPair deviceKp = rsa3072();
            f.devicePub = deviceKp.getPublic();
            f.akPrivate = akKp.getPrivate();

            int akAttrs = TpmPublicArea.OBJ_FIXED_TPM | TpmPublicArea.OBJ_FIXED_PARENT
                    | TpmPublicArea.OBJ_SENSITIVE_DATA_ORIGIN | TpmPublicArea.OBJ_RESTRICTED | TpmPublicArea.OBJ_SIGN;
            int deviceAttrs = TpmPublicArea.OBJ_FIXED_TPM | TpmPublicArea.OBJ_SENSITIVE_DATA_ORIGIN
                    | TpmPublicArea.OBJ_SIGN; // unrestricted leaf signing key
            f.akTpm2b = rsaTpmtPublic((RSAPublicKey) akKp.getPublic(), TpmPublicArea.ALG_RSASSA, akAttrs);
            f.deviceTpm2b = rsaTpmtPublic((RSAPublicKey) deviceKp.getPublic(), TpmPublicArea.ALG_NULL, deviceAttrs);

            TpmPublicArea akParsed = TpmPublicArea.parse(f.akTpm2b, true);
            TpmPublicArea deviceParsed = TpmPublicArea.parse(f.deviceTpm2b, true);
            if (!akParsed.isRestrictedSigningKey()) {
                throw new IllegalStateException("fixture AK is not a restricted signing key");
            }
            f.akName = akParsed.computeName();
            byte[] deviceName = deviceParsed.computeName();

            f.certifyInfo = attestCertify(deviceName);
            f.certifySig = rsaTpmtSignature(jcaSign(akKp.getPrivate(), f.certifyInfo));
            f.quote = attestQuote(f.nonce);
            f.quoteSig = rsaTpmtSignature(jcaSign(akKp.getPrivate(), f.quote));

            f.bindingContext = DeviceKeySessionBindingContext.compute(
                    SESSION_ID, CHALLENGE_ID, f.nonce, PEER_KEY, NOW + TTL);
            f.deviceKeySig = rsaTpmtSignature(jcaSign(deviceKp.getPrivate(), f.bindingContext));
            return f;
        }

        EndpointTpmDeviceBinding binding(UUID tenant, UUID device) {
            return new EndpointTpmDeviceBinding(tenant, device, UUID.randomUUID(), akName, sha256Hex(akTpm2b),
                    sha256Hex(goldenEkCertDer()), sha256Hex(devicePub.getEncoded()),
                    Instant.ofEpochMilli(NOW), Instant.ofEpochMilli(NOW));
        }

        TpmDeviceKeySessionAttestation attestation() {
            return attestationWith(deviceTpm2b, akTpm2b, akName, goldenEkCertDer(), certifyInfo, certifySig,
                    quote, quoteSig, bindingContext, deviceKeySig);
        }

        TpmDeviceKeySessionAttestation attestationWithBindingContext(byte[] ctx) {
            return attestationWith(deviceTpm2b, akTpm2b, akName, goldenEkCertDer(), certifyInfo, certifySig,
                    quote, quoteSig, ctx, deviceKeySig);
        }

        TpmDeviceKeySessionAttestation attestationWithDeviceKeySig(byte[] sig) {
            return attestationWith(deviceTpm2b, akTpm2b, akName, goldenEkCertDer(), certifyInfo, certifySig,
                    quote, quoteSig, bindingContext, sig);
        }

        TpmDeviceKeySessionAttestation attestationWithCertifySig(byte[] sig) {
            return attestationWith(deviceTpm2b, akTpm2b, akName, goldenEkCertDer(), certifyInfo, sig,
                    quote, quoteSig, bindingContext, deviceKeySig);
        }

        TpmDeviceKeySessionAttestation attestationWithQuoteNonce(byte[] otherNonce) {
            try {
                // a VALIDLY AK-signed quote, but over a DIFFERENT nonce than this challenge → the AK signature
                // verifies yet verifyQuote rejects on extraData != issued nonce (real replay/stale-nonce path)
                byte[] otherQuote = attestQuote(otherNonce);
                byte[] otherQuoteSig = rsaTpmtSignature(jcaSign(akPrivate, otherQuote));
                return attestationWith(deviceTpm2b, akTpm2b, akName, goldenEkCertDer(), certifyInfo, certifySig,
                        otherQuote, otherQuoteSig, bindingContext, deviceKeySig);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        TpmDeviceKeySessionAttestation attestationWithoutEkCert() {
            return attestationWith(deviceTpm2b, akTpm2b, akName, new byte[0], certifyInfo, certifySig,
                    quote, quoteSig, bindingContext, deviceKeySig);
        }

        TpmDeviceKeySessionAttestation attestationWithEkCert(byte[] ekDer) {
            return attestationWith(deviceTpm2b, akTpm2b, akName, ekDer, certifyInfo, certifySig,
                    quote, quoteSig, bindingContext, deviceKeySig);
        }

        TpmDeviceKeySessionAttestation attestationWithSchema(String schema) {
            return new TpmDeviceKeySessionAttestation(CHALLENGE_ID, schema, deviceTpm2b, akTpm2b, akName,
                    new byte[0], goldenEkCertDer(), List.of(), certifyInfo, certifySig, quote, quoteSig,
                    bindingContext, deviceKeySig, NOW);
        }

        private static TpmDeviceKeySessionAttestation attestationWith(byte[] deviceKeyPub, byte[] akPub, byte[] akName,
                byte[] ekCert, byte[] certifyInfo, byte[] certifySig, byte[] quote, byte[] quoteSig,
                byte[] bindingContext, byte[] deviceKeySig) {
            return new TpmDeviceKeySessionAttestation(CHALLENGE_ID,
                    DeviceKeyAttestationRealSessionDeviceTrustVerifier.RESPONSE_SCHEMA, deviceKeyPub, akPub,
                    akName, new byte[0], ekCert, List.of(), certifyInfo, certifySig, quote, quoteSig,
                    bindingContext, deviceKeySig, NOW);
        }
    }

    // ───────────────────────────── TPM marshaling (inverse of the production parsers) ─────────────────────────────

    private static byte[] rsaTpmtPublic(RSAPublicKey key, int scheme, int attrs) {
        byte[] modulus = unsignedBytes(key.getModulus());
        ByteArrayOutputStream tpmt = new ByteArrayOutputStream();
        putU16(tpmt, TpmPublicArea.ALG_RSA);
        putU16(tpmt, TpmPublicArea.ALG_SHA256); // nameAlg
        putU32(tpmt, attrs);
        putU16(tpmt, 0); // authPolicy TPM2B (empty)
        putU16(tpmt, TpmPublicArea.ALG_NULL); // symmetric NULL
        putU16(tpmt, scheme);
        if (scheme != TpmPublicArea.ALG_NULL) {
            putU16(tpmt, TpmPublicArea.ALG_SHA256); // scheme hash
        }
        putU16(tpmt, key.getModulus().bitLength()); // keyBits
        putU32(tpmt, 0); // exponent 0 → default 65537 (matches a default-F4 JCA key)
        putU16(tpmt, modulus.length);
        tpmt.writeBytes(modulus);
        return tpm2b(tpmt.toByteArray());
    }

    private static byte[] attestCertify(byte[] certifiedName) {
        ByteArrayOutputStream a = new ByteArrayOutputStream();
        putU32(a, 0xFF544347L); // TPM_GENERATED
        putU16(a, 0x8017); // ST_ATTEST_CERTIFY
        putU16(a, 0); // qualifiedSigner TPM2B (empty)
        putU16(a, 0); // extraData TPM2B (empty)
        a.writeBytes(new byte[17]); // clockInfo
        a.writeBytes(new byte[8]); // firmwareVersion
        putU16(a, certifiedName.length);
        a.writeBytes(certifiedName);
        putU16(a, 0); // qualifiedName TPM2B (empty)
        return a.toByteArray();
    }

    private static byte[] attestQuote(byte[] nonce) {
        ByteArrayOutputStream a = new ByteArrayOutputStream();
        putU32(a, 0xFF544347L);
        putU16(a, 0x8018); // ST_ATTEST_QUOTE
        putU16(a, 0); // qualifiedSigner
        putU16(a, nonce.length); // extraData = nonce
        a.writeBytes(nonce);
        a.writeBytes(new byte[17]); // clockInfo
        a.writeBytes(new byte[8]); // firmwareVersion
        putU32(a, 0); // TPML_PCR_SELECTION count = 0
        putU16(a, 0); // pcrDigest TPM2B (empty)
        return a.toByteArray();
    }

    private static byte[] rsaTpmtSignature(byte[] rsaSig) {
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        putU16(s, TpmPublicArea.ALG_RSASSA);
        putU16(s, TpmPublicArea.ALG_SHA256);
        putU16(s, rsaSig.length);
        s.writeBytes(rsaSig);
        return s.toByteArray();
    }

    private static byte[] jcaSign(PrivateKey key, byte[] data) throws Exception {
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(key);
        s.update(data);
        return s.sign();
    }

    private static byte[] tpm2b(byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        putU16(out, body.length);
        out.writeBytes(body);
        return out.toByteArray();
    }

    private static byte[] unsignedBytes(BigInteger v) {
        byte[] b = v.toByteArray();
        if (b.length > 1 && b[0] == 0x00) {
            byte[] t = new byte[b.length - 1];
            System.arraycopy(b, 1, t, 0, t.length);
            return t;
        }
        return b;
    }

    private static void putU16(ByteArrayOutputStream o, int v) {
        o.write((v >>> 8) & 0xFF);
        o.write(v & 0xFF);
    }

    private static void putU32(ByteArrayOutputStream o, long v) {
        o.write((int) ((v >>> 24) & 0xFF));
        o.write((int) ((v >>> 16) & 0xFF));
        o.write((int) ((v >>> 8) & 0xFF));
        o.write((int) (v & 0xFF));
    }
}

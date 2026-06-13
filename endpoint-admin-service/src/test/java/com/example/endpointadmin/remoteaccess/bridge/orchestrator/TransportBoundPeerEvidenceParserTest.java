package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.AttestationEvidence;
import com.example.endpointadmin.remoteaccess.CertRef;
import com.example.endpointadmin.remoteaccess.CertThumbprint;
import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.AgentHello;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.PeerEvidenceParser.ParsedEvidence;
import com.example.endpointadmin.remoteaccess.bridge.server.PeerIdentity;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Faz 22.6 D10.1 (#634, Codex 019ec29a) — the {@link TransportBoundPeerEvidenceParser} acceptance matrix. It
 * proves the parser only ever produces STRUCTURED evidence bound to the authenticated transport (never a trust
 * decision), and that every malformed/forged input fails CLOSED to empty (so the ledger's verifiers then deny):
 * <ul>
 *   <li>CertRef is the mTLS transport leaf — the self-claimed {@code hello.certFingerprint} is never read;</li>
 *   <li>a chain leaf that is NOT the authenticated peer (thumbprint ≠ transportPeerKey) → no CertRef;</li>
 *   <li>attestation is strict-decoded (bounded, strict Base64, exactly 4 SLSA fields) or it is empty;</li>
 *   <li>the deviceKey is ALWAYS empty (device trust is the DB machine-cert slice, #634 3b).</li>
 * </ul>
 */
class TransportBoundPeerEvidenceParserTest {

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private static final AtomicLong SERIAL = new AtomicLong(System.nanoTime());

    private final TransportBoundPeerEvidenceParser parser = new TransportBoundPeerEvidenceParser();

    // ---- helpers --------------------------------------------------------

    /** An ephemeral self-signed EC P-256 cert — generated per call, nothing committed (Codex boundary). */
    private static X509Certificate selfSigned(String cn) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(256);
            KeyPair keys = generator.generateKeyPair();
            X500Name dn = new X500Name("CN=" + cn);
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    dn, BigInteger.valueOf(SERIAL.incrementAndGet()),
                    Date.from(Instant.now().minusSeconds(60)), Date.from(Instant.now().plusSeconds(3600)),
                    dn, keys.getPublic());
            ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA")
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME).build(keys.getPrivate());
            return new JcaX509CertificateConverter().setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .getCertificate(builder.build(signer));
        } catch (Exception e) {
            throw new IllegalStateException("test cert generation failed", e);
        }
    }

    private static String thumbprint(X509Certificate cert) {
        try {
            return CertThumbprint.ofDer(cert.getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(String raw) {
        return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** A hello with a (deliberately bogus) self-claimed certFingerprint + the given attestation payload. */
    private static AgentHello hello(String selfClaimedFingerprint, String attestationB64) {
        return new AgentHello("1.0.0", "dev-1", selfClaimedFingerprint, attestationB64, "rb-v1",
                Set.of(RemoteSessionCapability.VIEW_ONLY));
    }

    // ---- CertRef from the transport leaf --------------------------------

    @Test
    void certRefIsBuiltFromTheAuthenticatedTransportLeaf() {
        X509Certificate leaf = selfSigned("agent-leaf");
        String transportKey = thumbprint(leaf);
        PeerIdentity peer = new PeerIdentity(transportKey, Optional.of("dev-1"), List.of(leaf));

        ParsedEvidence parsed = parser.parse(peer, hello("ignored-self-claim", null));

        assertTrue(parsed.certRef().isPresent(), "the transport leaf is the certRef");
        CertRef ref = parsed.certRef().orElseThrow();
        assertEquals(transportKey, ref.thumbprint());
        assertEquals("SHA-256", ref.thumbprintAlg());
        assertEquals(leaf.getSerialNumber().toString(16), ref.serialNumber());
        assertEquals(1, ref.encodedChain().size());
        try {
            assertArrayEquals(leaf.getEncoded(), ref.encodedChain().get(0));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void theSelfClaimedHelloCertFingerprintIsNeverUsed() {
        X509Certificate leaf = selfSigned("agent-leaf");
        String transportKey = thumbprint(leaf);
        // hello claims a DIFFERENT fingerprint — the parser must bind to the transport, not the self-claim
        PeerIdentity peer = new PeerIdentity(transportKey, Optional.empty(), List.of(leaf));

        ParsedEvidence parsed = parser.parse(peer, hello(thumbprint(selfSigned("attacker")), null));

        assertEquals(transportKey, parsed.certRef().orElseThrow().thumbprint(),
                "certRef thumbprint is the transport leaf, never the hello self-claim");
    }

    @Test
    void aChainLeafThatIsNotTheTransportPeerIsRejected() {
        X509Certificate transportLeaf = selfSigned("real-agent");
        X509Certificate forgedLeaf = selfSigned("forged-agent");
        // the chain presents a forged leaf, but the authenticated transport key is the real leaf's
        PeerIdentity peer = new PeerIdentity(thumbprint(transportLeaf), Optional.empty(), List.of(forgedLeaf));

        assertTrue(parser.parse(peer, hello(null, null)).certRef().isEmpty(),
                "a chain leaf that is not the authenticated peer is a forged claim → no evidence");
    }

    @Test
    void aHostileCertThatThrowsMidBuildYieldsNoCertRefRatherThanThrowing() {
        // the parser contract is TOTAL: a cert provider that throws (here from getSerialNumber) must fail closed
        // to empty, never propagate (Codex 019ec29a post-impl hardening)
        X509Certificate real = selfSigned("agent-leaf");
        byte[] der;
        try {
            der = real.getEncoded();
        } catch (CertificateEncodingException e) {
            throw new IllegalStateException(e);
        }
        X509Certificate hostile = mock(X509Certificate.class);
        try {
            when(hostile.getEncoded()).thenReturn(der); // thumbprint matches transportPeerKey → passes the guard
        } catch (CertificateEncodingException e) {
            throw new IllegalStateException(e);
        }
        when(hostile.getSerialNumber()).thenThrow(new RuntimeException("hostile provider"));
        PeerIdentity peer = new PeerIdentity(thumbprint(real), Optional.empty(), List.of(hostile));

        assertTrue(parser.parse(peer, hello(null, null)).certRef().isEmpty(),
                "a provider that throws mid-build yields no evidence (the parser never throws)");
    }

    @Test
    void anInvalidUtf8AttestationYieldsEmpty() {
        X509Certificate leaf = selfSigned("agent-leaf");
        PeerIdentity peer = new PeerIdentity(thumbprint(leaf), Optional.empty(), List.of(leaf));
        // valid Base64 whose DECODED bytes are not well-formed UTF-8 (0xFF/0xFE are never valid UTF-8) → strict
        // decode REPORTs → no attestation (the "strict-decoded" claim is literal)
        String invalidUtf8B64 = Base64.getEncoder().encodeToString(new byte[] {(byte) 0xFF, (byte) 0xFE});
        assertTrue(parser.parse(peer, hello(null, invalidUtf8B64)).attestation().isEmpty());
    }

    @Test
    void theFullChainIsPreservedInOrder() {
        X509Certificate leaf = selfSigned("agent-leaf");
        X509Certificate intermediate = selfSigned("agent-ca");
        PeerIdentity peer = new PeerIdentity(thumbprint(leaf), Optional.empty(), List.of(leaf, intermediate));

        CertRef ref = parser.parse(peer, hello(null, null)).certRef().orElseThrow();
        assertEquals(2, ref.encodedChain().size(), "leaf + intermediate preserved");
        try {
            assertArrayEquals(leaf.getEncoded(), ref.encodedChain().get(0));
            assertArrayEquals(intermediate.getEncoded(), ref.encodedChain().get(1));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void anEmptyChainYieldsNoCertRef() {
        X509Certificate leaf = selfSigned("agent-leaf");
        PeerIdentity peer = new PeerIdentity(thumbprint(leaf), Optional.empty(), List.of());
        assertTrue(parser.parse(peer, hello(null, null)).certRef().isEmpty());
    }

    @Test
    void aNullPeerOrNullHelloYieldsEmptyEvidence() {
        X509Certificate leaf = selfSigned("agent-leaf");
        PeerIdentity peer = new PeerIdentity(thumbprint(leaf), Optional.empty(), List.of(leaf));

        ParsedEvidence nullPeer = parser.parse(null, hello(null, b64("d|b|h|s")));
        assertTrue(nullPeer.certRef().isEmpty() && nullPeer.attestation().isEmpty()
                && nullPeer.deviceKey().isEmpty());

        ParsedEvidence nullHello = parser.parse(peer, null);
        assertTrue(nullHello.certRef().isEmpty() && nullHello.attestation().isEmpty()
                && nullHello.deviceKey().isEmpty());
    }

    // ---- attestation strict-decode --------------------------------------

    @Test
    void attestationIsStrictDecodedFromTheCanonicalFourFields() {
        X509Certificate leaf = selfSigned("agent-leaf");
        PeerIdentity peer = new PeerIdentity(thumbprint(leaf), Optional.empty(), List.of(leaf));

        ParsedEvidence parsed = parser.parse(peer,
                hello(null, b64("binDigest|builderX|predHash|sigZ")));

        AttestationEvidence att = parsed.attestation().orElseThrow();
        assertEquals("binDigest", att.binaryDigest());
        assertEquals("builderX", att.builderId());
        assertEquals("predHash", att.slsaPredicateHash());
        assertEquals("sigZ", att.predicateSignature());
        assertTrue(att.isComplete());
    }

    @Test
    void aBlankOrMissingAttestationYieldsEmpty() {
        X509Certificate leaf = selfSigned("agent-leaf");
        PeerIdentity peer = new PeerIdentity(thumbprint(leaf), Optional.empty(), List.of(leaf));
        assertTrue(parser.parse(peer, hello(null, null)).attestation().isEmpty());
        assertTrue(parser.parse(peer, hello(null, "")).attestation().isEmpty());
        assertTrue(parser.parse(peer, hello(null, "   ")).attestation().isEmpty());
    }

    @Test
    void aNonBase64AttestationYieldsEmpty() {
        X509Certificate leaf = selfSigned("agent-leaf");
        PeerIdentity peer = new PeerIdentity(thumbprint(leaf), Optional.empty(), List.of(leaf));
        assertTrue(parser.parse(peer, hello(null, "not!valid!base64!")).attestation().isEmpty());
    }

    @Test
    void aWrongFieldCountAttestationYieldsEmpty() {
        X509Certificate leaf = selfSigned("agent-leaf");
        PeerIdentity peer = new PeerIdentity(thumbprint(leaf), Optional.empty(), List.of(leaf));
        assertTrue(parser.parse(peer, hello(null, b64("only|three|fields"))).attestation().isEmpty(),
                "3 fields → not the SLSA tuple → empty");
        assertTrue(parser.parse(peer, hello(null, b64("a|b|c|d|e"))).attestation().isEmpty(),
                "5 fields → empty");
    }

    @Test
    void anAttestationWithABlankBinaryDigestYieldsEmpty() {
        X509Certificate leaf = selfSigned("agent-leaf");
        PeerIdentity peer = new PeerIdentity(thumbprint(leaf), Optional.empty(), List.of(leaf));
        // 4 fields but the load-bearing binaryDigest is blank → AttestationEvidence.isPresent() == false
        assertTrue(parser.parse(peer, hello(null, b64("|builder|hash|sig"))).attestation().isEmpty());
    }

    @Test
    void anOversizedAttestationYieldsEmpty() {
        X509Certificate leaf = selfSigned("agent-leaf");
        PeerIdentity peer = new PeerIdentity(thumbprint(leaf), Optional.empty(), List.of(leaf));
        String huge = "A".repeat(9000); // > MAX_ATTESTATION_B64_LEN (8192)
        assertTrue(parser.parse(peer, hello(null, huge)).attestation().isEmpty(),
                "an oversized field is bounded → empty (no unbounded decode)");
    }

    // ---- device key is never produced here ------------------------------

    @Test
    void theDeviceKeyIsAlwaysEmpty() {
        X509Certificate leaf = selfSigned("agent-leaf");
        // even with a certBoundDeviceId present, the parser produces no device-key attestation (slice-3b)
        PeerIdentity peer = new PeerIdentity(thumbprint(leaf), Optional.of("dev-bound-id"), List.of(leaf));
        assertFalse(parser.parse(peer, hello(null, b64("d|b|h|s"))).deviceKey().isPresent(),
                "device trust is the DB machine-cert binding, a separate slice — not produced here");
    }
}

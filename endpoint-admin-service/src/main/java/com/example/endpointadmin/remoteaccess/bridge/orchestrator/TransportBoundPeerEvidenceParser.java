package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.AttestationEvidence;
import com.example.endpointadmin.remoteaccess.CertRef;
import com.example.endpointadmin.remoteaccess.CertThumbprint;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages;
import com.example.endpointadmin.remoteaccess.bridge.server.PeerIdentity;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Faz 22.6 D10.1 (#634, Codex 019ec29a) — the real {@link PeerEvidenceParser}: it extracts STRUCTURED evidence
 * from the authenticated transport + the agent's hello, so {@link PeerTrustLedger} can run the REAL verifiers.
 * It replaces {@link PeerEvidenceParser#FAIL_CLOSED} (which returned empty → every trust false → never PERMIT).
 *
 * <p><b>This is NOT a trust bypass</b> (Codex): the parser produces only {@link CertRef} / {@link AttestationEvidence}
 * — it NEVER sets {@code certTrusted/attestationVerified/deviceTrusted}. The ledger's verifiers decide trust; the
 * broker PERMITs only when all of cert + attestation + device trust hold (+ owner-grant + step-up + duress +
 * capability). The load-bearing invariants:
 * <ul>
 *   <li><b>CertRef from the TRANSPORT leaf</b> — built from {@code peer.chain()}, and the leaf's SHA-256-DER
 *       thumbprint MUST equal {@code peer.transportPeerKey()} (the mTLS-interceptor-derived key); a chain whose
 *       leaf is not the authenticated peer is a forged claim → fail-closed empty. {@code hello.certFingerprint}
 *       (self-claimed/advisory) is NEVER used as a trust input.</li>
 *   <li><b>Attestation strict-decoded</b> from {@code hello.attestationEvidenceB64()} — bounded length, strict
 *       Base64, exactly the 4 SLSA fields; any deviation → empty (the verifier then fails closed). Never logged.</li>
 *   <li><b>deviceKey stays empty</b> — the AgentHello carries no device-key attestation, and a {@code certBoundDeviceId}
 *       string is NOT a {@code DeviceKeyAttestation} (Codex); device trust is the DB machine-cert binding, a
 *       SEPARATE slice (#634 3b). So device trust stays false here — PERMIT remains gated on that slice.</li>
 * </ul>
 * A PLACEHOLDER for the non-prod pilot ({@link PeerEvidenceParserFactory} forbids it in production).
 */
public final class TransportBoundPeerEvidenceParser implements PeerEvidenceParser {

    /** A bound so a malformed/oversized hello field cannot drive unbounded work (never logged raw). */
    private static final int MAX_ATTESTATION_B64_LEN = 8192;

    @Override
    public ParsedEvidence parse(PeerIdentity peer, RemoteBridgeMessages.AgentHello hello) {
        if (peer == null || hello == null) {
            return ParsedEvidence.empty();
        }
        return new ParsedEvidence(certRefFromTransport(peer), parseAttestation(hello), Optional.empty());
    }

    private static Optional<CertRef> certRefFromTransport(PeerIdentity peer) {
        List<X509Certificate> chain = peer.chain();
        if (chain == null || chain.isEmpty()
                || peer.transportPeerKey() == null || peer.transportPeerKey().isBlank()) {
            return Optional.empty();
        }
        X509Certificate leaf = chain.get(0);
        try {
            String leafThumbprint = CertThumbprint.ofDer(leaf.getEncoded());
            // the chain's leaf MUST be the authenticated mTLS transport peer — else it is a forged claim
            if (!CertThumbprint.matches(leafThumbprint, peer.transportPeerKey())) {
                return Optional.empty();
            }
            List<byte[]> encodedChain = new ArrayList<>(chain.size());
            for (X509Certificate cert : chain) {
                encodedChain.add(cert.getEncoded());
            }
            return Optional.of(new CertRef(leafThumbprint, "SHA-256",
                    leaf.getSerialNumber().toString(16), leaf.getIssuerX500Principal().getName(), encodedChain));
        } catch (CertificateEncodingException | RuntimeException cannotBuild) {
            // the parser contract is TOTAL (Codex 019ec29a post-impl): a malformed/hostile cert — un-encodable,
            // or a provider that throws from getSerialNumber()/getIssuerX500Principal() — is no evidence, never a
            // thrown parse. PeerTrustLedger also maps a parser throw to all-false, but the parser fails closed itself.
            return Optional.empty();
        }
    }

    private static Optional<AttestationEvidence> parseAttestation(RemoteBridgeMessages.AgentHello hello) {
        String b64 = hello.attestationEvidenceB64();
        if (b64 == null || b64.isBlank() || b64.length() > MAX_ATTESTATION_B64_LEN) {
            return Optional.empty();
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(b64.strip());
        } catch (IllegalArgumentException notStrictBase64) {
            return Optional.empty();
        }
        String text;
        try {
            text = strictUtf8(decoded); // invalid UTF-8 REPORTs (not a replacement char) → empty (Codex 019ec29a)
        } catch (CharacterCodingException notStrictUtf8) {
            return Optional.empty();
        }
        // canonical form: binaryDigest|builderId|slsaPredicateHash|predicateSignature (SLSA provenance fields);
        // exactly 4 segments, the binaryDigest present — else no attestation (the verifier fails closed)
        String[] fields = text.split("\\|", -1);
        if (fields.length != 4) {
            return Optional.empty();
        }
        AttestationEvidence evidence = new AttestationEvidence(fields[0], fields[1], fields[2], fields[3]);
        return evidence.isPresent() ? Optional.of(evidence) : Optional.empty();
    }

    /** Strict UTF-8 decode: a malformed/unmappable byte sequence REPORTs (throws) rather than silently becoming a
     *  replacement char, so an attestation payload that is not well-formed UTF-8 yields no attestation. */
    private static String strictUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }
}

package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.AttestationEvidence;
import com.example.endpointadmin.remoteaccess.CertRef;
import com.example.endpointadmin.remoteaccess.CertThumbprint;
import com.example.endpointadmin.remoteaccess.DeviceIdentityVerifier;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages;
import com.example.endpointadmin.remoteaccess.bridge.server.PeerIdentity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
 *       Base64, either the legacy 4 SLSA fields or a strict v1 JSON envelope; any deviation → empty (the verifier
 *       then fails closed). Never logged.</li>
 *   <li><b>Device-key attestation is parsed only from the v1 envelope</b> — a {@code certBoundDeviceId} string is
 *       NOT a {@code DeviceKeyAttestation}. Missing/malformed device-key fields stay empty; the verifier keeps
 *       hardware trust false until the agent presents real TPM/device-key evidence (#548).</li>
 * </ul>
 * A PLACEHOLDER for the non-prod pilot ({@link PeerEvidenceParserFactory} forbids it in production).
 */
public final class TransportBoundPeerEvidenceParser implements PeerEvidenceParser {

    /** A bound so a malformed/oversized hello field cannot drive unbounded work (never logged raw). */
    private static final int MAX_ATTESTATION_B64_LEN = 16 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ENVELOPE_FIELDS = Set.of("v", "slsa", "deviceKey");
    private static final Set<String> SLSA_FIELDS =
            Set.of("binaryDigest", "builderId", "slsaPredicateHash", "predicateSignature");
    private static final Set<String> DEVICE_KEY_FIELDS = Set.of(
            "keyDer", "protectionLevel", "nonExportable", "signature", "algorithm", "chainDer");

    @Override
    public ParsedEvidence parse(PeerIdentity peer, RemoteBridgeMessages.AgentHello hello) {
        if (peer == null || hello == null) {
            return ParsedEvidence.empty();
        }
        EvidencePayload payload = parseEvidencePayload(hello.attestationEvidenceB64());
        return new ParsedEvidence(certRefFromTransport(peer), payload.attestation(), payload.deviceKey());
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

    private static EvidencePayload parseEvidencePayload(String b64) {
        if (b64 == null || b64.isBlank() || b64.length() > MAX_ATTESTATION_B64_LEN) {
            return EvidencePayload.empty();
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(b64.strip());
        } catch (IllegalArgumentException notStrictBase64) {
            return EvidencePayload.empty();
        }
        String text;
        try {
            text = strictUtf8(decoded); // invalid UTF-8 REPORTs (not a replacement char) → empty (Codex 019ec29a)
        } catch (CharacterCodingException notStrictUtf8) {
            return EvidencePayload.empty();
        }
        String trimmed = text.stripLeading();
        if (trimmed.startsWith("{")) {
            return parseEnvelope(trimmed);
        }
        // canonical form: binaryDigest|builderId|slsaPredicateHash|predicateSignature (SLSA provenance fields);
        // exactly 4 segments, the binaryDigest present — else no attestation (the verifier fails closed)
        String[] fields = text.split("\\|", -1);
        if (fields.length != 4) {
            return EvidencePayload.empty();
        }
        AttestationEvidence evidence = new AttestationEvidence(fields[0], fields[1], fields[2], fields[3]);
        return evidence.isPresent()
                ? new EvidencePayload(Optional.of(evidence), Optional.empty())
                : EvidencePayload.empty();
    }

    private static EvidencePayload parseEnvelope(String json) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (Exception malformedJson) {
            return EvidencePayload.empty();
        }
        if (!root.isObject() || !hasOnlyFields(root, ENVELOPE_FIELDS)) {
            return EvidencePayload.empty();
        }
        JsonNode version = root.get("v");
        if (version == null || !version.canConvertToInt() || version.asInt() != 1) {
            return EvidencePayload.empty();
        }
        Optional<AttestationEvidence> attestation = Optional.empty();
        JsonNode slsa = root.get("slsa");
        if (slsa != null && !slsa.isNull()) {
            attestation = parseSlsaObject(slsa);
        }
        Optional<DeviceIdentityVerifier.DeviceKeyAttestation> deviceKey = Optional.empty();
        JsonNode deviceKeyNode = root.get("deviceKey");
        if (deviceKeyNode != null && !deviceKeyNode.isNull()) {
            deviceKey = parseDeviceKeyObject(deviceKeyNode);
        }
        return new EvidencePayload(attestation, deviceKey);
    }

    private static Optional<AttestationEvidence> parseSlsaObject(JsonNode slsa) {
        if (!slsa.isObject() || !hasOnlyFields(slsa, SLSA_FIELDS)) {
            return Optional.empty();
        }
        AttestationEvidence evidence = new AttestationEvidence(
                text(slsa, "binaryDigest"),
                text(slsa, "builderId"),
                text(slsa, "slsaPredicateHash"),
                text(slsa, "predicateSignature"));
        return evidence.isPresent() ? Optional.of(evidence) : Optional.empty();
    }

    private static Optional<DeviceIdentityVerifier.DeviceKeyAttestation> parseDeviceKeyObject(JsonNode deviceKey) {
        if (!deviceKey.isObject() || !hasOnlyFields(deviceKey, DEVICE_KEY_FIELDS)) {
            return Optional.empty();
        }
        Optional<byte[]> keyDer = decodeB64(text(deviceKey, "keyDer"));
        Optional<byte[]> signature = decodeB64(text(deviceKey, "signature"));
        Optional<List<byte[]>> chain = decodeB64Array(deviceKey.get("chainDer"));
        Optional<DeviceIdentityVerifier.DeviceProtectionLevel> level =
                protectionLevel(text(deviceKey, "protectionLevel"));
        JsonNode nonExportableNode = deviceKey.get("nonExportable");
        if (keyDer.isEmpty() || signature.isEmpty() || chain.isEmpty() || level.isEmpty()
                || nonExportableNode == null || !nonExportableNode.isBoolean()) {
            return Optional.empty();
        }
        String algorithm = text(deviceKey, "algorithm");
        if (algorithm == null || algorithm.isBlank()) {
            return Optional.empty();
        }
        DeviceIdentityVerifier.DeviceKeyAttestation attestation =
                new DeviceIdentityVerifier.DeviceKeyAttestation(
                        keyDer.get(), level.get(), nonExportableNode.booleanValue(), signature.get(), algorithm,
                        chain.get());
        return attestation.isComplete() ? Optional.of(attestation) : Optional.empty();
    }

    private static boolean hasOnlyFields(JsonNode node, Set<String> allowed) {
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            if (!allowed.contains(names.next())) {
                return false;
            }
        }
        return true;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static Optional<byte[]> decodeB64(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            return decoded.length > 0 ? Optional.of(decoded) : Optional.empty();
        } catch (IllegalArgumentException notStrictBase64) {
            return Optional.empty();
        }
    }

    private static Optional<List<byte[]>> decodeB64Array(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return Optional.empty();
        }
        List<byte[]> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                return Optional.empty();
            }
            Optional<byte[]> decoded = decodeB64(item.textValue());
            if (decoded.isEmpty()) {
                return Optional.empty();
            }
            values.add(decoded.get());
        }
        return Optional.of(List.copyOf(values));
    }

    private static Optional<DeviceIdentityVerifier.DeviceProtectionLevel> protectionLevel(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(DeviceIdentityVerifier.DeviceProtectionLevel.valueOf(value));
        } catch (IllegalArgumentException unknownLevel) {
            return Optional.empty();
        }
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

    private record EvidencePayload(Optional<AttestationEvidence> attestation,
                                   Optional<DeviceIdentityVerifier.DeviceKeyAttestation> deviceKey) {
        private static EvidencePayload empty() {
            return new EvidencePayload(Optional.empty(), Optional.empty());
        }
    }
}

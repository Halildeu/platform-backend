package com.example.endpointadmin.remoteaccess;

import com.example.endpointadmin.remoteaccess.AttestationVerifier.AttestationDecision;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Faz 22.6 B1.4c-2 — verifies a REAL in-toto / DSSE SLSA-provenance envelope (the format cosign / the SLSA
 * toolchain emits), building on the B1.4c-1 signature primitive. JDK + Jackson only (no Sigstore client),
 * offline + deterministic.
 *
 * <p>It parses the DSSE envelope JSON, extracts the in-toto Statement (subject digest, predicateType, the
 * builder id), checks the builder + predicate type against the configured expectations, then verifies the
 * envelope signature over the canonical DSSE <b>PAE</b> (Pre-Authentication Encoding) — NOT over the raw
 * payload, exactly as the DSSE spec requires — with the configured trusted {@link PublicKey}. A holder of
 * the build-system private key is the only party that can produce a verifying signature.
 *
 * <p><b>Fail-closed</b> (Codex doctrine): a {@code null}/empty/malformed envelope, a missing required field,
 * or any thrown exception → {@link AttestationDecision#MISSING} (no usable provenance); an untrusted /
 * mid-session-revoked builder → UNTRUSTED_BUILDER; a predicate-type mismatch → POLICY_MISMATCH; a present
 * envelope whose signature does not verify over the PAE → SIGNATURE_INVALID; only a complete, trusted,
 * correct-policy, validly-signed envelope → VERIFIED. The verifier DICTATES the algorithm (allowlisted at
 * construction); the untrusted envelope never selects it.
 *
 * <p><b>Scope (B1.4c-2):</b> a single static trusted key; the trusted-key RING + key-id selection + the
 * IN_MEMORY/KEY_BASED/DSSE selector are the B1.4c-3 factory. Keyless Sigstore (Fulcio cert + Rekor
 * transparency log — needs network) is a further seam.
 */
public final class DsseProvenanceVerifier {

    private static final String PAE_PREFIX = "DSSEv1";

    private final ObjectMapper mapper = new ObjectMapper();
    private final String expectedBuilderId;
    private final String expectedPredicateType;
    private final PublicKey signingKey;
    private final String signatureAlgorithm;
    private final Set<String> revokedBuilders = ConcurrentHashMap.newKeySet();

    public DsseProvenanceVerifier(String expectedBuilderId, String expectedPredicateType,
                                  PublicKey signingKey, String signatureAlgorithm) {
        if (expectedBuilderId == null || expectedBuilderId.isBlank()
                || expectedPredicateType == null || expectedPredicateType.isBlank()) {
            throw new IllegalArgumentException("expectedBuilderId + expectedPredicateType must be non-blank");
        }
        if (signingKey == null) {
            throw new IllegalArgumentException("signingKey must be non-null");
        }
        this.expectedBuilderId = expectedBuilderId;
        this.expectedPredicateType = expectedPredicateType;
        this.signingKey = signingKey;
        this.signatureAlgorithm = SignatureAlgorithms.require(signatureAlgorithm);
    }

    /** Revoke a builder mid-session (compromised-builder disclosure) → its sessions fail on next heartbeat. */
    public void revokeBuilder(String builderId) {
        if (builderId != null && !builderId.isBlank()) {
            revokedBuilders.add(builderId);
        }
    }

    /**
     * The DSSE Pre-Authentication Encoding: {@code "DSSEv1" SP LEN(type) SP type SP LEN(body) SP body}, where
     * the lengths are decimal ASCII of the UTF-8 byte length and {@code body} is the RAW (decoded) payload.
     * This is what the envelope signature is computed over (binds the payload type to the body).
     */
    public static byte[] pae(String payloadType, byte[] payload) {
        byte[] type = payloadType.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(PAE_PREFIX.getBytes(StandardCharsets.UTF_8));
        out.writeBytes((" " + type.length + " ").getBytes(StandardCharsets.UTF_8));
        out.writeBytes(type);
        out.writeBytes((" " + payload.length + " ").getBytes(StandardCharsets.UTF_8));
        out.writeBytes(payload);
        return out.toByteArray();
    }

    /** Verify a DSSE-encoded SLSA provenance envelope at {@code now}. Fail-closed for every failure path. */
    public AttestationDecision verify(byte[] dsseEnvelopeJson, Instant now) {
        if (dsseEnvelopeJson == null || dsseEnvelopeJson.length == 0 || now == null) {
            return AttestationDecision.MISSING;
        }
        try {
            JsonNode envelope = mapper.readTree(dsseEnvelopeJson);
            String payloadType = textOrNull(envelope, "payloadType");
            String payloadBase64 = textOrNull(envelope, "payload");
            JsonNode signatures = envelope.path("signatures");
            if (payloadType == null || payloadBase64 == null || !signatures.isArray() || signatures.isEmpty()) {
                return AttestationDecision.MISSING; // not a well-formed DSSE envelope → no usable provenance
            }
            byte[] payload = Base64.getDecoder().decode(payloadBase64);
            JsonNode statement = mapper.readTree(payload);

            String builderId = nodeText(statement, "predicate", "builder", "id");
            String predicateType = nodeText(statement, "predicateType");
            String subjectDigest = statement.path("subject").path(0).path("digest").path("sha256").asText(null);
            if (isBlank(builderId) || isBlank(predicateType) || isBlank(subjectDigest)) {
                return AttestationDecision.MISSING; // incomplete in-toto Statement
            }
            if (revokedBuilders.contains(builderId) || !expectedBuilderId.equals(builderId)) {
                return AttestationDecision.UNTRUSTED_BUILDER;
            }
            if (!expectedPredicateType.equals(predicateType)) {
                return AttestationDecision.POLICY_MISMATCH; // not the expected SLSA predicate type/version
            }
            byte[] pae = pae(payloadType, payload);
            return anySignatureVerifies(signatures, pae)
                    ? AttestationDecision.VERIFIED
                    : AttestationDecision.SIGNATURE_INVALID;
        } catch (IOException | RuntimeException e) {
            return AttestationDecision.MISSING; // malformed JSON / bad base64 / unexpected shape → no provenance
        }
    }

    /** True iff at least one envelope signature verifies over the PAE with the configured key (fail-closed). */
    private boolean anySignatureVerifies(JsonNode signatures, byte[] pae) {
        for (JsonNode sigNode : signatures) {
            String sigBase64 = sigNode.path("sig").asText(null);
            if (isBlank(sigBase64)) {
                continue;
            }
            try {
                byte[] sig = Base64.getDecoder().decode(sigBase64);
                Signature verifier = Signature.getInstance(signatureAlgorithm);
                verifier.initVerify(signingKey);
                verifier.update(pae);
                if (verifier.verify(sig)) {
                    return true;
                }
            } catch (GeneralSecurityException | RuntimeException e) {
                // this signature didn't verify (bad base64 / wrong key / etc.) — try the next, else fail-closed
            }
        }
        return false;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isTextual() ? v.asText() : null;
    }

    private static String nodeText(JsonNode root, String... path) {
        JsonNode n = root;
        for (String p : path) {
            n = n.path(p);
        }
        return n.isTextual() ? n.asText() : null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

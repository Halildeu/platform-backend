package com.example.endpointadmin.remoteaccess;

import com.example.endpointadmin.remoteaccess.AttestationVerifier.AttestationDecision;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Faz 22.6 B1.4c-2 — {@link DsseProvenanceVerifier} real in-toto/DSSE SLSA-envelope verification. A DSSE
 * envelope is built + signed over the PAE in-test with an EC P-256 keypair (offline, deterministic-in-outcome).
 */
class DsseProvenanceVerifierTest {

    private static final String BUILDER = "https://builder.acik/fleet";
    private static final String PREDICATE_TYPE = "https://slsa.dev/provenance/v1";
    private static final String DIGEST = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final String PAYLOAD_TYPE = "application/vnd.in-toto+json";
    private static final String ALG = "SHA256withECDSA";
    private static final Instant NOW = Instant.parse("2026-06-11T12:00:00Z");

    private final ObjectMapper json = new ObjectMapper();
    private final KeyPair keyPair = ecKeyPair();
    private final DsseProvenanceVerifier verifier =
            new DsseProvenanceVerifier(BUILDER, PREDICATE_TYPE, keyPair.getPublic(), ALG);

    private static KeyPair ecKeyPair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(new ECGenParameterSpec("secp256r1"));
            return kpg.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] statement(String digest, String builder, String predicateType) {
        try {
            ObjectNode stmt = json.createObjectNode();
            stmt.put("_type", "https://in-toto.io/Statement/v1");
            ObjectNode subject = stmt.putArray("subject").addObject();
            subject.put("name", "agent-binary");
            subject.putObject("digest").put("sha256", digest);
            stmt.put("predicateType", predicateType);
            stmt.putObject("predicate").putObject("builder").put("id", builder);
            return json.writeValueAsBytes(stmt);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] envelope(byte[] payload, PrivateKey signingKey) {
        try {
            Signature s = Signature.getInstance(ALG);
            s.initSign(signingKey);
            s.update(DsseProvenanceVerifier.pae(PAYLOAD_TYPE, payload));
            String sig = Base64.getEncoder().encodeToString(s.sign());
            ObjectNode env = json.createObjectNode();
            env.put("payloadType", PAYLOAD_TYPE);
            env.put("payload", Base64.getEncoder().encodeToString(payload));
            ArrayNode sigs = env.putArray("signatures");
            sigs.addObject().put("keyid", "test-key").put("sig", sig);
            return json.writeValueAsBytes(env);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A signed envelope over the given payload, signed with our trusted key. */
    private byte[] signedEnvelope(byte[] payload) {
        return envelope(payload, keyPair.getPrivate());
    }

    @Test
    void verifiedForAValidSignedSlsaEnvelope() {
        byte[] env = signedEnvelope(statement(DIGEST, BUILDER, PREDICATE_TYPE));
        assertEquals(AttestationDecision.VERIFIED, verifier.verify(env, NOW));
    }

    @Test
    void signatureInvalidWhenTheEnvelopePayloadIsTamperedAfterSigning() {
        // sign the PAE of the ORIGINAL payload, then swap in a different payload (same builder + policy, so it
        // reaches the signature check) → the PAE no longer matches → SIGNATURE_INVALID
        byte[] original = statement(DIGEST, BUILDER, PREDICATE_TYPE);
        try {
            Signature s = Signature.getInstance(ALG);
            s.initSign(keyPair.getPrivate());
            s.update(DsseProvenanceVerifier.pae(PAYLOAD_TYPE, original));
            String sig = Base64.getEncoder().encodeToString(s.sign());
            byte[] tampered = statement("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                    BUILDER, PREDICATE_TYPE);
            ObjectNode env = json.createObjectNode();
            env.put("payloadType", PAYLOAD_TYPE);
            env.put("payload", Base64.getEncoder().encodeToString(tampered)); // tampered body, original sig
            env.putArray("signatures").addObject().put("sig", sig);
            assertEquals(AttestationDecision.SIGNATURE_INVALID, verifier.verify(json.writeValueAsBytes(env), NOW));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void signatureInvalidForAWrongSigningKey() {
        byte[] env = envelope(statement(DIGEST, BUILDER, PREDICATE_TYPE), ecKeyPair().getPrivate());
        assertEquals(AttestationDecision.SIGNATURE_INVALID, verifier.verify(env, NOW));
    }

    @Test
    void untrustedForWrongBuilder() {
        byte[] env = signedEnvelope(statement(DIGEST, "https://evil.example/builder", PREDICATE_TYPE));
        assertEquals(AttestationDecision.UNTRUSTED_BUILDER, verifier.verify(env, NOW));
    }

    @Test
    void revokedBuilderIsUntrustedContinuously() {
        byte[] env = signedEnvelope(statement(DIGEST, BUILDER, PREDICATE_TYPE));
        assertEquals(AttestationDecision.VERIFIED, verifier.verify(env, NOW));
        verifier.revokeBuilder(BUILDER);
        assertEquals(AttestationDecision.UNTRUSTED_BUILDER, verifier.verify(env, NOW));
    }

    @Test
    void policyMismatchForWrongPredicateType() {
        byte[] env = signedEnvelope(statement(DIGEST, BUILDER, "https://slsa.dev/provenance/v0.2"));
        assertEquals(AttestationDecision.POLICY_MISMATCH, verifier.verify(env, NOW));
    }

    @Test
    void missingForMalformedOrEmptyOrNullEnvelope() {
        assertEquals(AttestationDecision.MISSING, verifier.verify(null, NOW));
        assertEquals(AttestationDecision.MISSING, verifier.verify(new byte[0], NOW));
        assertEquals(AttestationDecision.MISSING,
                verifier.verify("{not valid json".getBytes(StandardCharsets.UTF_8), NOW));
        assertEquals(AttestationDecision.MISSING,
                verifier.verify("{\"payloadType\":\"x\"}".getBytes(StandardCharsets.UTF_8), NOW)); // no payload/sigs
    }

    @Test
    void missingForAnIncompleteStatement() {
        // a well-formed envelope but the in-toto Statement is missing the subject digest
        byte[] noDigest = statement("", BUILDER, PREDICATE_TYPE);
        assertEquals(AttestationDecision.MISSING, verifier.verify(signedEnvelope(noDigest), NOW));
    }

    @Test
    void missingForNullNow() {
        assertEquals(AttestationDecision.MISSING,
                verifier.verify(signedEnvelope(statement(DIGEST, BUILDER, PREDICATE_TYPE)), null));
    }

    @Test
    void missingForANonTextualDigest() throws Exception {
        // Codex 019eb7d6 type-guard: a non-string sha256 (a number) must NOT silently coerce → MISSING
        ObjectNode stmt = json.createObjectNode();
        stmt.put("_type", "https://in-toto.io/Statement/v1");
        ObjectNode subject = stmt.putArray("subject").addObject();
        subject.put("name", "agent-binary");
        subject.putObject("digest").put("sha256", 1234567890); // a NUMBER, not a string
        stmt.put("predicateType", PREDICATE_TYPE);
        stmt.putObject("predicate").putObject("builder").put("id", BUILDER);
        byte[] env = signedEnvelope(json.writeValueAsBytes(stmt));
        assertEquals(AttestationDecision.MISSING, verifier.verify(env, NOW));
    }

    @Test
    void paeMatchesTheDsseSpec() {
        // PAE = "DSSEv1" SP LEN(type) SP type SP LEN(body) SP body
        byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] expected = ("DSSEv1 4 abcd 5 hello").getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(expected, DsseProvenanceVerifier.pae("abcd", body));
    }
}

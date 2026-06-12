package com.example.endpointadmin.remoteaccess;

import com.example.endpointadmin.remoteaccess.OperatorStepUpPolicy.MethodStrength;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Objects;

/**
 * Faz 22.6 D operator step-up verifier seam (d-stepup-2, Codex 019ebe06 S3) — the REAL WebAuthn assertion
 * verifier, replacing the d-stepup-1 in-memory placeholder. Deterministic + offline + fail-closed; the
 * configured operator {@link PublicKey} is the trust anchor. Mirrors the B1.4c-1 {@code
 * KeyBasedAttestationVerifier} pattern (shared {@link SignatureAlgorithms} allowlist + JCA {@link Signature}).
 *
 * <p><b>Minimum deterministic WebAuthn (Codex S3):</b>
 * <ul>
 *   <li>{@code clientDataJSON}: parsed JSON — {@code type} MUST be {@code "webauthn.get"}, {@code challenge}
 *       (base64url) MUST equal the broker-issued challenge, {@code origin} MUST equal the configured origin;</li>
 *   <li>{@code authenticatorData}: the flags byte — User-Present (UP, bit 0) MUST be set; User-Verified (UV,
 *       bit 2) decides the strength ({@code WEBAUTHN_USER_VERIFICATION} when set, else
 *       {@code WEBAUTHN_USER_PRESENCE});</li>
 *   <li>signature: a JCA verify over {@code authenticatorData || SHA-256(clientDataJSON)} with the operator
 *       key (the WebAuthn assertion signature base).</li>
 * </ul>
 * The full registration/attestation ceremony + signature-counter replay defence are DEFERRED (live, stateful).
 *
 * <p><b>Fail-closed:</b> any null/blank/malformed input, a type/challenge/origin mismatch, a missing UP flag,
 * an unparseable signature, or a verification failure yields a non-VERIFIED {@link StepUpVerification} — never
 * throws, never returns null (every parse/crypto path is wrapped).
 */
public final class WebAuthnStepUpVerifier implements OperatorStepUpVerifier {

    /** authenticatorData layout: 32-byte rpIdHash + 1 flags byte + 4 signCount = 37 minimum. */
    private static final int AUTH_DATA_MIN_LEN = 37;
    private static final int FLAGS_OFFSET = 32;
    private static final int FLAG_USER_PRESENT = 0x01;
    private static final int FLAG_USER_VERIFIED = 0x04;
    private static final String EXPECTED_TYPE = "webauthn.get";

    private final ObjectMapper mapper = new ObjectMapper();
    private final PublicKey operatorKey;
    private final String signatureAlgorithm;
    private final String expectedOrigin;
    private final byte[] expectedRpIdHash;

    public WebAuthnStepUpVerifier(PublicKey operatorKey, String signatureAlgorithm, String expectedOrigin,
                                  String expectedRpId) {
        this.operatorKey = Objects.requireNonNull(operatorKey, "operatorKey");
        this.signatureAlgorithm = SignatureAlgorithms.require(signatureAlgorithm); // shared allowlist (B1.4c)
        if (expectedOrigin == null || expectedOrigin.isBlank()) {
            throw new IllegalArgumentException("expectedOrigin is required (origin pinning is mandatory)");
        }
        if (expectedRpId == null || expectedRpId.isBlank()) {
            throw new IllegalArgumentException("expectedRpId is required (relying-party pinning is mandatory)");
        }
        this.expectedOrigin = expectedOrigin;
        // the authenticatorData binds the assertion to SHA-256(rpId) — precompute the expected hash to compare
        // the first 32 bytes against, so an assertion minted for a DIFFERENT relying party is refused (Codex)
        this.expectedRpIdHash = sha256(expectedRpId.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public StepUpVerification verify(StepUpChallenge challenge, StepUpAssertion assertion, long nowEpochMillis) {
        if (challenge == null || assertion == null) {
            return StepUpVerification.refused(Verdict.MISSING);
        }
        if (isBlank(challenge.challengeB64()) || isBlank(assertion.clientDataJsonB64())
                || isBlank(assertion.authenticatorDataB64()) || isBlank(assertion.signatureB64())) {
            return StepUpVerification.refused(Verdict.MALFORMED);
        }
        try {
            byte[] clientDataBytes = Base64.getDecoder().decode(assertion.clientDataJsonB64());
            JsonNode clientData = mapper.readTree(clientDataBytes);

            if (!EXPECTED_TYPE.equals(clientData.path("type").asText())) {
                return StepUpVerification.refused(Verdict.MALFORMED); // not an assertion ("webauthn.get")
            }
            if (!challengeMatches(challenge.challengeB64(), clientData.path("challenge").asText(null))) {
                return StepUpVerification.refused(Verdict.CHALLENGE_MISMATCH);
            }
            if (!expectedOrigin.equals(clientData.path("origin").asText(null))) {
                return StepUpVerification.refused(Verdict.ORIGIN_MISMATCH);
            }

            byte[] authData = Base64.getDecoder().decode(assertion.authenticatorDataB64());
            if (authData.length < AUTH_DATA_MIN_LEN) {
                return StepUpVerification.refused(Verdict.MALFORMED);
            }
            // the assertion is bound to a relying party by SHA-256(rpId) in the first 32 bytes — an assertion
            // minted for a different RP must be refused (Codex), constant-time compared
            byte[] presentedRpIdHash = java.util.Arrays.copyOfRange(authData, 0, 32);
            if (!MessageDigest.isEqual(presentedRpIdHash, expectedRpIdHash)) {
                return StepUpVerification.refused(Verdict.RP_ID_MISMATCH);
            }
            int flags = authData[FLAGS_OFFSET] & 0xFF;
            if ((flags & FLAG_USER_PRESENT) == 0) {
                return StepUpVerification.refused(Verdict.USER_PRESENCE_MISSING);
            }

            byte[] signature = Base64.getDecoder().decode(assertion.signatureB64());
            if (!signatureVerifies(authData, clientDataBytes, signature)) {
                return StepUpVerification.refused(Verdict.SIGNATURE_INVALID);
            }

            MethodStrength strength = (flags & FLAG_USER_VERIFIED) != 0
                    ? MethodStrength.WEBAUTHN_USER_VERIFICATION : MethodStrength.WEBAUTHN_USER_PRESENCE;
            return StepUpVerification.verified(strength, nowEpochMillis);
        } catch (IOException | RuntimeException e) {
            // any parse/decode/crypto failure is fail-closed — never throw out of verify()
            return StepUpVerification.refused(Verdict.MALFORMED);
        }
    }

    /** The signed data is {@code authenticatorData || SHA-256(clientDataJSON)} (the WebAuthn assertion base). */
    private boolean signatureVerifies(byte[] authData, byte[] clientDataBytes, byte[] signature) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] clientDataHash = sha256.digest(clientDataBytes);
            byte[] signed = new byte[authData.length + clientDataHash.length];
            System.arraycopy(authData, 0, signed, 0, authData.length);
            System.arraycopy(clientDataHash, 0, signed, authData.length, clientDataHash.length);

            Signature verifier = Signature.getInstance(signatureAlgorithm);
            verifier.initVerify(operatorKey);
            verifier.update(signed);
            return verifier.verify(signature);
        } catch (java.security.GeneralSecurityException e) {
            return false; // a key/alg/signature error is a failed verification, not a thrown error
        }
    }

    /**
     * The broker challenge is compared to the assertion's {@code challenge} by RAW BYTES: the broker side is a
     * standard Base64 string, the WebAuthn clientData side is base64url (no padding) — decode both, compare
     * constant-time. A decode failure is a mismatch (fail-closed).
     */
    private static boolean challengeMatches(String expectedB64, String clientDataChallengeB64Url) {
        if (clientDataChallengeB64Url == null) {
            return false;
        }
        try {
            byte[] expected = Base64.getDecoder().decode(expectedB64);
            byte[] actual = Base64.getUrlDecoder().decode(clientDataChallengeB64Url);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e); // never happens on a JDK
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

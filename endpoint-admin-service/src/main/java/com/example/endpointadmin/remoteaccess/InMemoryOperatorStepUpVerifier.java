package com.example.endpointadmin.remoteaccess;

import com.example.endpointadmin.remoteaccess.OperatorStepUpPolicy.MethodStrength;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Faz 22.6 D operator step-up verifier seam (Codex 019ebe06) — the in-memory REFERENCE verifier (foundation
 * slice d-stepup-1). Deterministic + fail-closed, but a PLACEHOLDER trust basis: it does NOT parse real
 * WebAuthn {@code clientDataJSON}/{@code authenticatorData} nor verify a real assertion signature — that is the
 * d-stepup-2 {@code WebAuthnStepUpVerifier} (configured operator public key + JCA signature over
 * {@code authenticatorData || SHA256(clientDataJSON)}). This reference exists so the policy CONSUMER and the
 * seam are testable before the real verifier + the operator-facing transport land. It is NOT final assurance
 * and the factory (d-stepup-3) MUST forbid it in a prod-like profile (the B1.3a InMemoryAttestationVerifier
 * precedent).
 *
 * <p>Stand-in verification: a deterministic placeholder signature {@code Base64(SHA-256(challenge || clientData))},
 * constant-time compared. Origin must match the configured expected origin; the granted strength is the
 * configured ceiling (a reference verifier cannot actually distinguish user-presence from user-verification —
 * the real authenticatorData UP/UV flags do, in d-stepup-2).
 */
public final class InMemoryOperatorStepUpVerifier implements OperatorStepUpVerifier {

    private final MethodStrength grantedStrength;
    private final String expectedOrigin;

    /**
     * @param grantedStrength the non-NONE {@link MethodStrength} a successful reference verification grants (a
     *                        real verifier derives this from the authenticator UP/UV flags instead)
     * @param expectedOrigin  the WebAuthn origin the challenge MUST carry — required (origin pinning is
     *                        mandatory; a null/blank origin is fail-open and refused at construction)
     */
    public InMemoryOperatorStepUpVerifier(MethodStrength grantedStrength, String expectedOrigin) {
        // a reference verifier that grants NONE is pointless; a VERIFIED must carry a real strength
        if (grantedStrength == null || grantedStrength == MethodStrength.NONE) {
            throw new IllegalArgumentException("grantedStrength must be a non-NONE method strength");
        }
        // origin pinning is MANDATORY — an unpinned origin is fail-open (a WebAuthn assertion is only
        // meaningful against a fixed origin); missing config must fail-closed, not allow any origin (Codex REVISE)
        if (expectedOrigin == null || expectedOrigin.isBlank()) {
            throw new IllegalArgumentException("expectedOrigin is required (origin pinning is mandatory)");
        }
        this.grantedStrength = grantedStrength;
        this.expectedOrigin = expectedOrigin;
    }

    @Override
    public StepUpVerification verify(StepUpChallenge challenge, StepUpAssertion assertion, long nowEpochMillis) {
        if (challenge == null || assertion == null) {
            return StepUpVerification.refused(Verdict.MISSING);
        }
        if (isBlank(challenge.challengeB64()) || isBlank(assertion.signatureB64())
                || isBlank(assertion.clientDataJsonB64()) || isBlank(assertion.authenticatorDataB64())) {
            return StepUpVerification.refused(Verdict.MALFORMED);
        }
        if (!expectedOrigin.equals(challenge.expectedOrigin())) {
            return StepUpVerification.refused(Verdict.ORIGIN_MISMATCH);
        }
        String expected = placeholderSignature(challenge.challengeB64(), assertion.clientDataJsonB64());
        if (expected == null || !constantTimeEquals(expected, assertion.signatureB64())) {
            return StepUpVerification.refused(Verdict.SIGNATURE_INVALID);
        }
        return StepUpVerification.verified(grantedStrength, nowEpochMillis);
    }

    /** A deterministic stand-in for a real WebAuthn assertion signature — NOT cryptographic assurance. */
    private static String placeholderSignature(String challengeB64, String clientDataJsonB64) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha256.digest((challengeB64 + "|" + clientDataJsonB64).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            return null; // SHA-256 is always present; a null forces SIGNATURE_INVALID (fail-closed)
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

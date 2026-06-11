package com.example.endpointadmin.remoteaccess;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.cert.TrustAnchor;
import java.time.Duration;
import java.util.Set;

/**
 * Faz 22.6 B1.4a-3 — selects + safely constructs the {@link CertTrustEvaluator} from config, enforcing the
 * Codex 019eb6d9 blocking matrix at construction (i.e. at bean creation = STARTUP, so a mis-configured
 * runtime FAILS FAST rather than silently always-NOT_TRUSTED):
 *
 * <ul>
 *   <li>{@code IN_MEMORY} → the modelled {@link InMemoryCertTrustEvaluator} (current default).</li>
 *   <li>{@code REAL_PKI} is legal ONLY with a real revocation mode (CRL/OCSP). Every other REAL_PKI
 *       combination is rejected at construction:
 *     <ul>
 *       <li>empty trust anchors → fail-fast (a real PKI runtime cannot boot with no root of trust);</li>
 *       <li>{@code revocation-mode=DISABLED} → forbidden UNLESS the explicit test-only escape
 *           {@code allow-insecure-no-revocation=true} is set (default false, prod-forbidden);</li>
 *       <li>{@code revocation-mode=CRL|OCSP} → not yet implemented (B1.4b) → rejected so a half-wired
 *           revocation path can't masquerade as enforced.</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>So in B1.4a-3 the ONLY constructible {@code REAL_PKI} is the test-only DISABLED+override; production
 * stays on {@code IN_MEMORY} until B1.4b makes {@code REAL_PKI + CRL/OCSP} a legal combination.
 */
public final class CertTrustEvaluatorFactory {

    private static final Logger log = LoggerFactory.getLogger(CertTrustEvaluatorFactory.class);

    public enum EvaluatorType { IN_MEMORY, REAL_PKI }

    public enum RevocationMode { DISABLED, CRL, OCSP }

    /** Emit an audit event for a fail-fast config rejection, then raise it (Codex 019eb6d9). */
    private static IllegalStateException reject(String message) {
        log.error("remote-access cert-trust config REJECTED (fail-fast): {}", message);
        return new IllegalStateException(message);
    }

    /**
     * @param type                     evaluator selector ({@code null} → {@link EvaluatorType#IN_MEMORY},
     *                                 the safe default)
     * @param revocationMode           {@code null} → {@link RevocationMode#DISABLED}
     * @param anchors                  loaded trust anchors (only consulted for REAL_PKI)
     * @param allowInsecureNoRevocation the explicit test-only escape for REAL_PKI + DISABLED
     * @param productionLikeProfile    when true, the insecure escape is REFUSED even if the flag is set
     *                                 (Codex 019eb6d9: a stray test flag must never weaken a prod-like runtime)
     * @param inMemoryMaxAge           freshness window for the IN_MEMORY evaluator
     * @throws IllegalStateException on any forbidden REAL_PKI combination (fail-fast startup)
     */
    public static CertTrustEvaluator create(EvaluatorType type, RevocationMode revocationMode,
                                            Set<TrustAnchor> anchors, boolean allowInsecureNoRevocation,
                                            boolean productionLikeProfile, Duration inMemoryMaxAge) {
        EvaluatorType t = type == null ? EvaluatorType.IN_MEMORY : type;
        if (t == EvaluatorType.IN_MEMORY) {
            return new InMemoryCertTrustEvaluator(inMemoryMaxAge);
        }
        // REAL_PKI from here.
        if (anchors == null || anchors.isEmpty()) {
            throw reject("REAL_PKI requires configured trust anchors, but none were loaded "
                    + "(endpoint-admin.remote-access.cert-trust.trust-anchor-pem) — a real PKI runtime must not "
                    + "boot with no root of trust");
        }
        RevocationMode mode = revocationMode == null ? RevocationMode.DISABLED : revocationMode;
        switch (mode) {
            case CRL, OCSP -> throw reject("REAL_PKI revocation-mode=" + mode + " is not yet implemented "
                    + "(B1.4b: live CRL/OCSP) — refusing to boot a half-wired revocation path");
            case DISABLED -> {
                if (!allowInsecureNoRevocation) {
                    throw reject("REAL_PKI + revocation-mode=DISABLED is forbidden: REAL_PKI is legal ONLY with "
                            + "CRL/OCSP (B1.4b). For a test-only run without revocation set "
                            + "endpoint-admin.remote-access.cert-trust.allow-insecure-no-revocation=true "
                            + "(default false, prod-forbidden)");
                }
                if (productionLikeProfile) {
                    throw reject("REAL_PKI + revocation-mode=DISABLED + allow-insecure-no-revocation=true is "
                            + "REFUSED in a production-like profile — the no-revocation escape is test-only and "
                            + "must never be honored in prod (Codex 019eb6d9). Use REAL_PKI + CRL/OCSP (B1.4b)");
                }
                // test-only (non-prod profile): real chain validation + EKU enforcement, revocation OFF.
                return new CertPathTrustEvaluator(anchors, false, true);
            }
            default -> throw reject("unreachable revocation mode " + mode);
        }
    }

    private CertTrustEvaluatorFactory() {
    }
}

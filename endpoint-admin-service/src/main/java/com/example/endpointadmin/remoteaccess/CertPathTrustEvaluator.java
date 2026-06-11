package com.example.endpointadmin.remoteaccess;

import java.security.GeneralSecurityException;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertStore;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateRevokedException;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXParameters;
import java.security.cert.PKIXRevocationChecker;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * Faz 22.6 B1.4a-2 — the REAL X.509 {@link CertTrustEvaluator}: builds + validates the presented client-cert
 * chain against the configured trust anchors with the JDK {@link CertPathValidator} (PKIX). Replaces the
 * thumbprint-only {@link InMemoryCertTrustEvaluator} model with an actual cryptographic path-build. JDK-only
 * (no BouncyCastle). Deterministic + offline (validated against the committed fixture corpus).
 *
 * <p><b>Fail-closed (Codex 019eb6d9 a-2 tightening):</b> ANY failure — a {@code null} cert, an empty/
 * malformed chain (parse throws), no configured anchors, no path to an anchor, or ANY other crypto error —
 * maps to a NON-{@link TrustDecision#ALLOW} verdict. There is no partial-accept: only a fully built + valid
 * path returns {@link TrustDecision#ALLOW}. The precise cause is refined where the validator reports it
 * (EXPIRED / REVOKED / undetermined-revocation → UNKNOWN), else NOT_TRUSTED.
 *
 * <p><b>Revocation (B1.4a-2 scope):</b> {@code revocationEnabled=false} here — CRL/OCSP is the B1.4b slice.
 * The B1.4a-3 driver selector makes {@code REAL_PKI} legal ONLY with a revocation mode, so this evaluator is
 * never wired into a runtime with revocation off except under an explicit test-only override.
 */
public final class CertPathTrustEvaluator implements CertTrustEvaluator {

    private static final String X509 = "X.509";
    private static final String PKIX = "PKIX";

    /** RFC 5280 EKU OID for TLS client authentication; {@code anyExtendedKeyUsage} also satisfies it. */
    private static final String EKU_CLIENT_AUTH = "1.3.6.1.5.5.7.3.2";
    private static final String EKU_ANY = "2.5.29.37.0";

    private final Set<TrustAnchor> trustAnchors;
    private final boolean revocationEnabled;
    private final boolean requireClientAuth;
    private final List<X509CRL> crls;

    /** Secure-by-default: enforces the {@code clientAuth} EKU + {@code digitalSignature} key-usage. */
    public CertPathTrustEvaluator(Set<TrustAnchor> trustAnchors, boolean revocationEnabled) {
        this(trustAnchors, revocationEnabled, true, List.of());
    }

    /**
     * @param requireClientAuth when true (B1.4a-3, Codex 019eb6d9), a chain that builds + validates is STILL
     *                          rejected unless the leaf permits TLS client authentication (EKU {@code clientAuth}
     *                          or {@code anyExtendedKeyUsage}) AND its key-usage permits {@code digitalSignature}
     *                          — closing the "right chain but wrong purpose" gap (PKIX does not auto-enforce the
     *                          application purpose). A leaf with no EKU extension is rejected (strict).
     */
    public CertPathTrustEvaluator(Set<TrustAnchor> trustAnchors, boolean revocationEnabled,
                                  boolean requireClientAuth) {
        this(trustAnchors, revocationEnabled, requireClientAuth, List.of());
    }

    /**
     * @param crls the CRLs consulted when {@code revocationEnabled} (B1.4b). Offline, CRL-only, NO network
     *             fallback: a revoked serial → REVOKED; if revocation is enabled but no CRL covers the cert
     *             → UNDETERMINED → UNKNOWN (fail-closed, no grace — B1.2 doctrine). Live OCSP is a later seam.
     */
    public CertPathTrustEvaluator(Set<TrustAnchor> trustAnchors, boolean revocationEnabled,
                                  boolean requireClientAuth, List<X509CRL> crls) {
        // copy defensively; an empty anchor set is allowed here (→ NOT_TRUSTED at evaluate-time). The B1.4a-3
        // driver adds the fail-FAST startup check so a REAL_PKI runtime can't BOOT with no anchors.
        this.trustAnchors = trustAnchors == null ? Set.of() : Set.copyOf(trustAnchors);
        this.revocationEnabled = revocationEnabled;
        this.requireClientAuth = requireClientAuth;
        this.crls = crls == null ? List.of() : List.copyOf(crls);
    }

    @Override
    public TrustDecision evaluate(CertRef cert, Instant now) {
        if (cert == null || now == null) {
            return TrustDecision.NOT_TRUSTED; // nothing presented → trust nothing
        }
        List<X509Certificate> chain;
        try {
            chain = X509ChainParser.parseChain(cert.encodedChain());
        } catch (GeneralSecurityException | RuntimeException parseFailure) {
            // a-2 + Codex 019eb6d9: a malformed chain (or any unexpected runtime error) never proceeds
            return TrustDecision.NOT_TRUSTED;
        }
        if (chain.isEmpty() || trustAnchors.isEmpty()) {
            return TrustDecision.NOT_TRUSTED; // no chain, or no root of trust → trust nothing
        }
        try {
            CertPath path = CertificateFactory.getInstance(X509).generateCertPath(chain);
            PKIXParameters params = new PKIXParameters(trustAnchors);
            params.setDate(Date.from(now)); // validate AT the heartbeat's clock, not the JVM wall-clock
            CertPathValidator cpv = CertPathValidator.getInstance(PKIX);
            if (revocationEnabled) {
                // B1.4b: OFFLINE CRL revocation — CRL-only, NO network fallback (no live OCSP / CRL-DP fetch),
                // and ONLY_END_ENTITY: only the LEAF (the agent cert — the thing that gets compromised + must
                // be revocable fast) is checked against the CRL. The CA/intermediate certs are not CRL-checked
                // here — a compromised CA is handled out-of-band (removed from the trust anchors / intermediates),
                // and requiring a separate root-CRL for every path cert would otherwise make the leaf
                // UNDETERMINED. A revoked leaf serial → REVOKED; with NO_FALLBACK and no CRL covering the leaf
                // the verdict is UNDETERMINED_REVOCATION_STATUS → UNKNOWN (fail-closed, no grace — B1.2 doctrine).
                PKIXRevocationChecker rc = (PKIXRevocationChecker) cpv.getRevocationChecker();
                rc.setOptions(java.util.EnumSet.of(
                        PKIXRevocationChecker.Option.PREFER_CRLS,
                        PKIXRevocationChecker.Option.NO_FALLBACK,
                        PKIXRevocationChecker.Option.ONLY_END_ENTITY));
                params.addCertPathChecker(rc);
                if (!crls.isEmpty()) {
                    params.addCertStore(CertStore.getInstance(
                            "Collection", new CollectionCertStoreParameters(crls)));
                }
            } else {
                params.setRevocationEnabled(false); // B1.4a path: chain + validity only, no revocation
            }
            cpv.validate(path, params);
            // a fully built + valid path — but a valid chain to a trusted root is NOT enough: the leaf must be
            // PURPOSED for TLS client auth (Codex 019eb6d9). PKIX does not enforce the application purpose, so a
            // server / e-mail / code-signing cert that legitimately chains to the fleet CA must still be rejected.
            if (requireClientAuth && !permitsClientAuthentication(chain.get(0))) {
                return TrustDecision.NOT_TRUSTED; // right chain, wrong purpose
            }
            return TrustDecision.ALLOW;
        } catch (CertPathValidatorException e) {
            return classify(e);
        } catch (GeneralSecurityException | RuntimeException e) {
            return TrustDecision.NOT_TRUSTED; // any other crypto OR unexpected runtime error → fail-closed
        }
    }

    /**
     * Whether the leaf permits TLS client authentication: its EKU contains {@code clientAuth} (or
     * {@code anyExtendedKeyUsage}) AND, if a key-usage extension is present, {@code digitalSignature} is set.
     * Fail-closed: no EKU extension, or a parse error, → not permitted.
     */
    private static boolean permitsClientAuthentication(X509Certificate leaf) {
        try {
            List<String> eku = leaf.getExtendedKeyUsage(); // null = no EKU extension
            if (eku == null || !(eku.contains(EKU_CLIENT_AUTH) || eku.contains(EKU_ANY))) {
                return false; // not purposed for client auth (strict: an absent EKU is NOT a wildcard)
            }
            boolean[] keyUsage = leaf.getKeyUsage(); // null = no key-usage extension (unconstrained)
            // index 0 = digitalSignature; if the extension is present it must permit it
            return keyUsage == null || (keyUsage.length > 0 && keyUsage[0]);
        } catch (GeneralSecurityException | RuntimeException e) {
            return false; // a malformed EKU extension can't prove the purpose → fail-closed
        }
    }

    /** Map a validation failure to the precise verdict the validator reported (else NOT_TRUSTED). */
    private static TrustDecision classify(CertPathValidatorException e) {
        CertPathValidatorException.Reason reason = e.getReason();
        if (reason == CertPathValidatorException.BasicReason.EXPIRED) {
            return TrustDecision.EXPIRED;
        }
        if (reason == CertPathValidatorException.BasicReason.REVOKED) {
            return TrustDecision.REVOKED;
        }
        if (reason == CertPathValidatorException.BasicReason.UNDETERMINED_REVOCATION_STATUS) {
            return TrustDecision.UNKNOWN; // can't determine revocation → fail-closed (no grace, B1.2 doctrine)
        }
        // some providers signal validity failures via the cause chain without a refined reason:
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof CertificateExpiredException) {
                return TrustDecision.EXPIRED;
            }
            if (t instanceof CertificateRevokedException) {
                return TrustDecision.REVOKED;
            }
            // a not-yet-valid cert is a validity failure but NOT "expired" — there is no dedicated verdict,
            // so it falls through to NOT_TRUSTED (still fail-closed, just not mislabeled EXPIRED).
        }
        return TrustDecision.NOT_TRUSTED; // no path to an anchor / untrusted chain / not-yet-valid / other
    }
}

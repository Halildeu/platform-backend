package com.example.endpointadmin.tpmattest;

import java.security.MessageDigest;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * Faz 22.3B (ADR-0039) gate-4 — verifier V2: validate that a device's TPM
 * Endorsement Key (EK) certificate chains to a trusted manufacturer root
 * (design §4, §10.5 T-7/T-10).
 *
 * <p><b>Build-time root pinning (T-10):</b> the manufacturer-root bundle is
 * supplied as actual certificates AND each is verified at construction against
 * the pinned SHA-256 set ({@code endpoint-admin.tpm-attest.manufacturer-root-sha256}).
 * A bundle cert whose fingerprint is not pinned is rejected — the trust anchor
 * set can never silently widen. Supports a current+next dual-root window (T-7).
 *
 * <p><b>Configured intermediate bundle (Faz 22.6 #548 V2 path-building fix, Codex
 * {@code 019eff49} AGREE):</b> Intel fTPMs (e.g. CSME ADL PTT) issue the EK leaf
 * from an intermediate CA (e.g. {@code CSME ... PTT NNSVN}) whose cert is NOT
 * carried in the leaf, has no AIA, and on many devices is NOT readable from TPM
 * NV. So the agent legitimately submits a <b>leaf-only</b> chain. To still build
 * {@code leaf → intermediate(s) → pinned root}, the backend may be configured with
 * the manufacturer On-Die CA intermediate bundle
 * ({@code endpoint-admin.tpm-attest.manufacturer-intermediate-pems}). These are
 * <b>NEVER trust anchors</b> — only the pinned roots are. They are untrusted
 * path-building material in a {@link CertStore} (exactly like the agent-supplied
 * intermediates); a forged intermediate is useless because the built path must
 * still terminate at a pinned root. Optionally each configured intermediate is
 * verified at construction against {@code manufacturer-intermediate-sha256} — a
 * supply-chain/provenance guardrail (NOT a trust decision), fail-closed at startup.
 *
 * <p>Fail-closed: unknown/expired/untrusted EK chain → {@link TpmDenyCode#EK_UNTRUSTED}
 * via {@link EkChainException}. Revocation is disabled for the pilot (documented
 * limitation; an offline/cached CRL store is a production gate, NOT a runtime
 * fetch on the {@code /nonce} path — Codex {@code 019eff49}).
 */
public final class TpmEkChainValidator {

    /** Thrown on any EK-chain failure; maps to {@link TpmDenyCode#EK_UNTRUSTED}. */
    public static final class EkChainException extends Exception {
        public EkChainException(String message, Throwable cause) { super(message, cause); }
        public EkChainException(String message) { super(message); }
    }

    private final Set<TrustAnchor> trustAnchors;
    /** Untrusted path-building material (manufacturer On-Die CA intermediates). NEVER trust anchors. */
    private final List<X509Certificate> configuredIntermediates;

    /**
     * Root-only constructor (no configured intermediates) — back-compat for callers/tests that pre-date
     * the #548 intermediate-bundle path-building fix.
     */
    public TpmEkChainValidator(Set<String> pinnedRootSha256, List<X509Certificate> rootBundle)
            throws EkChainException {
        this(pinnedRootSha256, rootBundle, List.of(), Set.of());
    }

    /**
     * @param pinnedRootSha256 lowercase-hex SHA-256 fingerprints of the allowed trust-anchor roots
     * @param rootBundle the actual manufacturer root certificates (each MUST be pinned)
     * @param intermediateBundle manufacturer On-Die CA intermediate certs — untrusted path material
     *        (NEVER promoted to trust anchors)
     * @param pinnedIntermediateSha256 OPTIONAL lowercase-hex SHA-256 manifest of the configured
     *        intermediates (supply-chain guardrail). If non-empty, every configured intermediate's
     *        fingerprint MUST be present, else construction fail-closes. If empty, no per-intermediate
     *        pin check (the pinned root remains the sole trust boundary).
     * @throws EkChainException if a root cert's fingerprint is not pinned, no root is configured, or a
     *         configured intermediate is not in a non-empty pin manifest (config fail-closed)
     */
    public TpmEkChainValidator(Set<String> pinnedRootSha256, List<X509Certificate> rootBundle,
                               List<X509Certificate> intermediateBundle, Set<String> pinnedIntermediateSha256)
            throws EkChainException {
        Set<String> rootPins = normalizePins(pinnedRootSha256);
        Set<TrustAnchor> anchors = new HashSet<>();
        for (X509Certificate root : rootBundle) {
            String fp = sha256Hex(root);
            if (!rootPins.contains(fp)) {
                throw new EkChainException("root cert fingerprint " + fp + " is not in the pinned set");
            }
            anchors.add(new TrustAnchor(root, null));
        }
        if (anchors.isEmpty()) {
            throw new EkChainException("no trusted manufacturer roots configured");
        }
        this.trustAnchors = anchors;

        // Supply-chain guardrail on the configured intermediates (provenance, NOT trust): if a manifest
        // of pins is provided, every configured intermediate must match — otherwise fail-closed at startup
        // (no silent inclusion of an unexpected intermediate). These never become trust anchors regardless.
        Set<String> intPins = normalizePins(pinnedIntermediateSha256);
        List<X509Certificate> intermediates = new ArrayList<>();
        if (intermediateBundle != null) {
            for (X509Certificate inter : intermediateBundle) {
                if (!intPins.isEmpty() && !intPins.contains(sha256Hex(inter))) {
                    throw new EkChainException("configured intermediate fingerprint " + sha256Hex(inter)
                            + " is not in the pinned intermediate manifest");
                }
                intermediates.add(inter);
            }
        }
        this.configuredIntermediates = List.copyOf(intermediates);
    }

    /**
     * Validate the EK cert chains to a pinned root, building the path through the configured
     * manufacturer-intermediate bundle PLUS any agent-supplied intermediates. The EK leaf is the build
     * target; only the pinned roots are trust anchors; everything else is untrusted {@link CertStore}
     * material. A leaf-only submission (Intel fTPM) succeeds iff the configured bundle bridges it to a
     * pinned root.
     */
    public void validate(X509Certificate ekCert, List<X509Certificate> agentIntermediates) throws EkChainException {
        try {
            // Path-building material = the leaf + configured intermediates + agent-sent intermediates.
            // All UNTRUSTED — the only trust anchors are the pinned roots. (A forged intermediate cannot
            // change the outcome: the built path must still terminate at a pinned root.)
            List<X509Certificate> material = new ArrayList<>();
            material.add(ekCert);
            material.addAll(configuredIntermediates);
            if (agentIntermediates != null) {
                material.addAll(agentIntermediates);
            }

            X509CertSelector target = new X509CertSelector();
            target.setCertificate(ekCert); // build a path that starts at THIS exact EK leaf

            PKIXBuilderParameters params = new PKIXBuilderParameters(trustAnchors, target);
            params.addCertStore(CertStore.getInstance("Collection", new CollectionCertStoreParameters(material)));
            // CRL/OCSP layered in a later slice (R-3 SLO); pilot runs revocation-disabled (documented
            // limitation — Codex 019eff49). A runtime CRL fetch on /nonce would be a hard availability
            // dependency; production uses an offline/cached revocation store instead.
            params.setRevocationEnabled(false);

            // Throws if no path from the EK leaf to a pinned root can be built (fail-closed → EK_UNTRUSTED).
            CertPathBuilder.getInstance("PKIX").build(params);
        } catch (Exception e) {
            throw new EkChainException("EK certificate does not chain to a pinned manufacturer root", e);
        }
    }

    /** Parse a DER-encoded X.509 certificate. */
    public static X509Certificate parseCert(byte[] der) throws CertificateException {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
    }

    private static Set<String> normalizePins(Set<String> pins) {
        Set<String> out = new HashSet<>();
        if (pins != null) {
            for (String p : pins) {
                if (p != null && !p.isBlank()) {
                    out.add(p.toLowerCase().replace(":", "").trim());
                }
            }
        }
        return out;
    }

    private static String sha256Hex(X509Certificate cert) throws EkChainException {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
        } catch (Exception e) {
            throw new EkChainException("failed to fingerprint cert", e);
        }
    }
}

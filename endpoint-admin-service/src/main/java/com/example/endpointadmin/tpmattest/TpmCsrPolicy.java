package com.example.endpointadmin.tpmattest;

import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentVerifierProvider;
import org.bouncycastle.operator.DefaultAlgorithmNameFinder;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest;

import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Locale;

/**
 * Faz 22.3B (ADR-0039) gate-4a-2.4 — verifier <b>V9</b> (CSR key policy), design §4 line 85.
 * The agent's PKCS#10 CSR (the device identity key we will sign via Vault PKI) must, up-front and
 * fail-closed, satisfy:
 * <ol>
 *   <li><b>Proof-of-possession</b>: the CSR self-signature verifies (a forged/malformed CSR is rejected).</li>
 *   <li><b>Signature alg</b>: SHA-256+ (no SHA-1/MD5) and the alg family matches the key (no RSA↔ECC confusion).</li>
 *   <li><b>Key floor</b>: RSA-3072+ / EC-P256+ — {@link TpmAlgorithmPolicy.Role#DEVICE} (the issued-identity floor;
 *       this is where the strict device floor enforces in production).</li>
 *   <li><b>No scope expansion</b>: the only permitted requested extension is {@code extendedKeyUsage == {clientAuth}};
 *       {@code basicConstraints}, expansive {@code keyUsage}, and ANY other/unknown extension (critical or not) are
 *       rejected (defense-in-depth — Vault also overrides SAN/CN/EKU).</li>
 * </ol>
 * Every failure → {@link TpmDenyCode#CSR_POLICY_VIOLATION} (audit-only; uniform 403 at gate-4d).
 */
public final class TpmCsrPolicy {

    private TpmCsrPolicy() {}

    public static void verify(byte[] csrDer) {
        if (csrDer == null || csrDer.length == 0) {
            throw deny("CSR required");
        }
        PKCS10CertificationRequest csr;
        try {
            csr = new PKCS10CertificationRequest(csrDer);
        } catch (Exception e) {
            throw deny("malformed PKCS#10 CSR: " + e.getMessage(), e);
        }

        BouncyCastleProvider bc = new BouncyCastleProvider();
        PublicKey pub;
        try {
            pub = new JcaPKCS10CertificationRequest(csr).setProvider(bc).getPublicKey();
            ContentVerifierProvider cvp = new JcaContentVerifierProviderBuilder().setProvider(bc).build(pub);
            if (!csr.isSignatureValid(cvp)) {
                throw deny("CSR proof-of-possession signature invalid");
            }
        } catch (TpmAttestException t) {
            throw t;
        } catch (Exception e) {
            throw deny("CSR public-key / proof-of-possession error: " + e.getMessage(), e);
        }

        // Signature algorithm: SHA-256+ and family-consistent with the key.
        String sigAlg = new DefaultAlgorithmNameFinder()
                .getAlgorithmName(csr.getSignatureAlgorithm()).toUpperCase(Locale.ROOT);
        if (sigAlg.contains("SHA1") || sigAlg.contains("MD5")
                || !(sigAlg.contains("SHA256") || sigAlg.contains("SHA384") || sigAlg.contains("SHA512"))) {
            throw deny("CSR signature algorithm is not SHA-256+ (" + sigAlg + ")");
        }
        if (pub instanceof RSAPublicKey && !sigAlg.contains("RSA")) {
            throw deny("CSR algorithm confusion: RSA key signed with " + sigAlg);
        }
        if (pub instanceof ECPublicKey && !sigAlg.contains("ECDSA")) {
            throw deny("CSR algorithm confusion: EC key signed with " + sigAlg);
        }

        // Key floor (RSA-3072+ / EC-P256+). Maps WEAK_ALGORITHM → CSR_POLICY_VIOLATION here.
        try {
            TpmAlgorithmPolicy.requireKeyMeetsPolicy(pub, TpmAlgorithmPolicy.Role.DEVICE);
        } catch (TpmAttestException weak) {
            throw deny("CSR key below the device floor: " + weak.getMessage());
        }

        checkRequestedExtensions(csr);
    }

    /** Whitelist: only {@code extendedKeyUsage == {clientAuth}} is permitted; everything else is denied. */
    private static void checkRequestedExtensions(PKCS10CertificationRequest csr) {
        var attrs = csr.getAttributes(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest);
        if (attrs == null || attrs.length == 0) {
            return; // no requested extensions — fine (Vault adds clientAuth)
        }
        if (attrs.length > 1 || attrs[0].getAttrValues().size() != 1) {
            throw deny("malformed/duplicate extensionRequest attribute");
        }
        ASN1Encodable value = attrs[0].getAttrValues().getObjectAt(0);
        Extensions exts = Extensions.getInstance(value);
        for (ASN1ObjectIdentifier oid : exts.getExtensionOIDs()) {
            if (Extension.extendedKeyUsage.equals(oid)) {
                ExtendedKeyUsage eku = ExtendedKeyUsage.getInstance(exts.getExtension(oid).getParsedValue());
                KeyPurposeId[] purposes = eku.getUsages();
                if (purposes.length != 1 || !KeyPurposeId.id_kp_clientAuth.equals(purposes[0])) {
                    throw deny("CSR extendedKeyUsage must be exactly {clientAuth}");
                }
            } else if (Extension.basicConstraints.equals(oid)) {
                BasicConstraints bcExt = BasicConstraints.getInstance(exts.getExtension(oid).getParsedValue());
                throw deny(bcExt.isCA()
                        ? "CSR requests basicConstraints CA:true"
                        : "CSR requests basicConstraints (not allowed for a leaf clientAuth key)");
            } else {
                throw deny("CSR requests an unexpected extension: " + oid.getId());
            }
        }
    }

    private static TpmAttestException deny(String detail) {
        return new TpmAttestException(TpmDenyCode.CSR_POLICY_VIOLATION, detail);
    }

    private static TpmAttestException deny(String detail, Throwable cause) {
        return new TpmAttestException(TpmDenyCode.CSR_POLICY_VIOLATION, detail, cause);
    }
}

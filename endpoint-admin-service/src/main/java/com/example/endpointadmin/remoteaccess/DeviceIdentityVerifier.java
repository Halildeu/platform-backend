package com.example.endpointadmin.remoteaccess;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * Faz 22.6 B1.4d — the device-identity / TPM key-attestation verifier, the final piece of the B1 mTLS/PKI
 * layer (after B1.4a chain+identity, B1.4b CRL, B1.4c build-attestation). It answers "is the agent running on a
 * device whose key is hardware-bound (TPM / secure element / TEE), vouched for by a trusted device-attestation
 * CA?" — distinct from B1.4c (which attests the agent BINARY's build provenance) and from B1.2 (which trusts
 * the agent's mTLS CERT). This binds the session to a real device, not just a copyable cert/binary.
 *
 * <p><b>Scope cut (same pattern as every B1.4/D slice):</b> this is the deterministic OFFLINE policy — it
 * verifies a device-key attestation STATEMENT the agent presents (the attestation CA's signature over the
 * device key + its protection properties, and the CA chain to a configured device root). The LIVE
 * challenge-response that proves the device CURRENTLY controls the attested key (a fresh nonce signed by the
 * device key over the live transport) is a transport slice — like the cert chain, it only exists once the agent
 * mTLS transport terminates. Pure, total, fail-closed; reuses the B1.4a PKIX primitives
 * ({@link X509ChainParser}, {@link TrustAnchorLoader}) and the shared {@link SignatureAlgorithms} allowlist.
 *
 * <p><b>Fail-closed:</b> incomplete evidence → MISSING; an unparseable chain → MALFORMED; a chain that doesn't
 * build to a configured device-attestation root → UNTRUSTED_CHAIN; an attestation signature that doesn't verify
 * under the chain leaf's key → SIGNATURE_INVALID; a key weaker than the required protection level, or one the
 * attestation marks exportable → WEAK_PROTECTION. Only a fully validated, hardware-bound, strong-enough key is
 * {@link Verdict#TRUSTED}.
 */
public final class DeviceIdentityVerifier {

    /** Key-protection strength, totally ordered by an explicit {@link #rank()} (not enum ordinal). */
    public enum DeviceProtectionLevel {
        SOFTWARE(0),                 // a key in software / OS keystore — copyable, NOT device-bound
        TEE(1),                      // a Trusted Execution Environment (e.g. Android StrongBox-less TEE)
        SECURE_ELEMENT_OR_TPM(2);    // a discrete secure element / TPM — strongest, non-exfiltratable

        private final int rank;

        DeviceProtectionLevel(int rank) {
            this.rank = rank;
        }

        public int rank() {
            return rank;
        }

        public boolean meetsRequired(DeviceProtectionLevel required) {
            return required != null && this.rank >= required.rank();
        }
    }

    /** The explicit, auditable outcome of one device-identity check. */
    public enum Verdict {
        TRUSTED(true),
        MISSING(false),            // no / incomplete attestation evidence
        MALFORMED(false),          // an unparseable chain / device key
        UNTRUSTED_CHAIN(false),    // the attestation chain does not build to a configured device root
        SIGNATURE_INVALID(false),  // the attestation signature does not verify under the chain leaf's key
        WEAK_PROTECTION(false);    // the key is below the required protection level, or marked exportable

        private final boolean trusted;

        Verdict(boolean trusted) {
            this.trusted = trusted;
        }

        public boolean isTrusted() {
            return trusted;
        }
    }

    private static final String DOMAIN = "DeviceKeyAttestation:v1";
    private static final String X509 = "X.509";
    private static final String PKIX = "PKIX";

    /**
     * The device-key attestation the agent presents: the device public key (DER), its claimed protection
     * properties, the attestation CA's signature over those, the signature algorithm, and the attestation cert
     * chain (leaf = the attestation CA cert that produced the signature, then issuing intermediates).
     */
    public record DeviceKeyAttestation(byte[] deviceKeyDer,
                                       DeviceProtectionLevel claimedProtectionLevel,
                                       boolean nonExportable,
                                       byte[] attestationSignature,
                                       String signatureAlgorithm,
                                       List<byte[]> attestationChainDer) {

        public boolean isComplete() {
            return deviceKeyDer != null && deviceKeyDer.length > 0
                    && claimedProtectionLevel != null
                    && attestationSignature != null && attestationSignature.length > 0
                    && signatureAlgorithm != null && !signatureAlgorithm.isBlank()
                    && attestationChainDer != null && !attestationChainDer.isEmpty();
        }
    }

    private final Set<TrustAnchor> deviceRoots;
    private final DeviceProtectionLevel requiredProtection;

    /**
     * @param deviceRoots        the trusted device-attestation CA roots (a TPM manufacturer / MDM device CA);
     *                           empty → every chain is UNTRUSTED_CHAIN (fail-closed, trust nothing).
     * @param requiredProtection the minimum acceptable key-protection level (e.g. TEE or SECURE_ELEMENT_OR_TPM).
     */
    public DeviceIdentityVerifier(Set<TrustAnchor> deviceRoots, DeviceProtectionLevel requiredProtection) {
        if (requiredProtection == null) {
            throw new IllegalArgumentException("requiredProtection must not be null");
        }
        this.deviceRoots = deviceRoots == null ? Set.of() : Set.copyOf(deviceRoots);
        this.requiredProtection = requiredProtection;
    }

    /**
     * Verify the device-key attestation at {@code now} (the chain is validated at that instant, not the JVM
     * wall-clock). Total — never throws; fail-closed — anything not provably a trusted, hardware-bound,
     * strong-enough key is a non-TRUSTED verdict.
     */
    public Verdict verify(DeviceKeyAttestation attestation, Instant now) {
        if (attestation == null || now == null || !attestation.isComplete()) {
            return Verdict.MISSING;
        }
        List<X509Certificate> chain;
        try {
            chain = X509ChainParser.parseChain(attestation.attestationChainDer());
        } catch (GeneralSecurityException | RuntimeException e) {
            return Verdict.MALFORMED; // a malformed chain never proceeds
        }
        if (chain.isEmpty() || deviceRoots.isEmpty()) {
            return Verdict.UNTRUSTED_CHAIN; // no chain, or no root of trust → trust nothing
        }
        if (!chainBuildsToADeviceRoot(chain, now)) {
            return Verdict.UNTRUSTED_CHAIN;
        }
        if (!attestationSignatureVerifies(attestation, chain.get(0).getPublicKey())) {
            return Verdict.SIGNATURE_INVALID;
        }
        if (!attestation.nonExportable()
                || !attestation.claimedProtectionLevel().meetsRequired(requiredProtection)) {
            return Verdict.WEAK_PROTECTION;
        }
        return Verdict.TRUSTED;
    }

    /** PKIX path-build of the attestation chain to a configured device root (no revocation in this slice). */
    private boolean chainBuildsToADeviceRoot(List<X509Certificate> chain, Instant now) {
        try {
            CertPath path = CertificateFactory.getInstance(X509).generateCertPath(chain);
            PKIXParameters params = new PKIXParameters(deviceRoots);
            params.setRevocationEnabled(false); // offline slice; device-CA revocation = a transport/CRL follow-up
            params.setDate(Date.from(now));
            CertPathValidator.getInstance(PKIX).validate(path, params);
            return true;
        } catch (GeneralSecurityException | RuntimeException e) {
            return false; // any path/validation/crypto error → fail-closed
        }
    }

    /** Verify the attestation CA's signature over the canonical (device key + protection properties). */
    private boolean attestationSignatureVerifies(DeviceKeyAttestation att, PublicKey attestationCaKey) {
        try {
            Signature sig = Signature.getInstance(SignatureAlgorithms.require(att.signatureAlgorithm()));
            sig.initVerify(attestationCaKey);
            sig.update(canonical(att));
            return sig.verify(att.attestationSignature());
        } catch (GeneralSecurityException | RuntimeException e) {
            return false; // a bad alg / key / signature → fail-closed (NOT trusted)
        }
    }

    /** Length-prefixed canonical (4-byte BE len + bytes per field) — delimiter-safe, domain-separated. */
    private static byte[] canonical(DeviceKeyAttestation att) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(out)) {
            writeField(dos, DOMAIN.getBytes(StandardCharsets.UTF_8));
            writeField(dos, att.deviceKeyDer());
            writeField(dos, att.claimedProtectionLevel().name().getBytes(StandardCharsets.UTF_8));
            writeField(dos, Boolean.toString(att.nonExportable()).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e); // ByteArrayOutputStream never throws
        }
        return out.toByteArray();
    }

    private static void writeField(DataOutputStream dos, byte[] bytes) throws IOException {
        dos.writeInt(bytes.length);
        dos.write(bytes);
    }
}

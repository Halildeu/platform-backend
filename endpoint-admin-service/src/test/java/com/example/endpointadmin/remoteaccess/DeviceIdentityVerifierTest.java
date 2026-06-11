package com.example.endpointadmin.remoteaccess;

import com.example.endpointadmin.remoteaccess.DeviceIdentityVerifier.DeviceKeyAttestation;
import com.example.endpointadmin.remoteaccess.DeviceIdentityVerifier.DeviceProtectionLevel;
import com.example.endpointadmin.remoteaccess.DeviceIdentityVerifier.Verdict;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 B1.4d — {@link DeviceIdentityVerifier} against an OFFLINE device-attestation fixture corpus
 * (EC P-256 self-signed device root + a device key + two real signatures; NO private key committed — the
 * signatures were pre-computed at fixture-build time, the verifier only verifies).
 */
class DeviceIdentityVerifierTest {

    private static final String ALG = "SHA256withECDSA";
    private static final Instant NOW = Instant.parse("2035-06-01T00:00:00Z"); // within the root cert validity

    private static byte[] res(String name) throws Exception {
        try (InputStream in = DeviceIdentityVerifierTest.class
                .getResourceAsStream("/remoteaccess/device-attestation/" + name)) {
            return in.readAllBytes();
        }
    }

    private final X509Certificate root;
    private final List<byte[]> chain;
    private final Set<TrustAnchor> anchors;
    private final byte[] deviceKey;
    private final byte[] sigSeTpm;
    private final byte[] sigSoftware;

    DeviceIdentityVerifierTest() throws Exception {
        this.root = X509ChainParser.parseCertificate(res("device-attestation-root.pem"));
        this.chain = List.of(root.getEncoded());
        this.anchors = TrustAnchorLoader.load(List.of(root.getEncoded()));
        this.deviceKey = res("device-key.der");
        this.sigSeTpm = res("sig-se-tpm-true.bin");
        this.sigSoftware = res("sig-software-true.bin");
    }

    private DeviceKeyAttestation att(DeviceProtectionLevel level, boolean nonExportable, byte[] sig, List<byte[]> ch) {
        return new DeviceKeyAttestation(deviceKey, level, nonExportable, sig, ALG, ch);
    }

    @Test
    void aHardwareBoundKeyVouchedByATrustedDeviceRootIsTrusted() {
        DeviceIdentityVerifier v = new DeviceIdentityVerifier(anchors, DeviceProtectionLevel.TEE);
        assertEquals(Verdict.TRUSTED,
                v.verify(att(DeviceProtectionLevel.SECURE_ELEMENT_OR_TPM, true, sigSeTpm, chain), NOW));
    }

    @Test
    void aKeyBelowTheRequiredProtectionLevelIsWeak() {
        // the SOFTWARE-level attestation is correctly signed, so the signature verifies — but SOFTWARE < TEE
        DeviceIdentityVerifier v = new DeviceIdentityVerifier(anchors, DeviceProtectionLevel.TEE);
        assertEquals(Verdict.WEAK_PROTECTION,
                v.verify(att(DeviceProtectionLevel.SOFTWARE, true, sigSoftware, chain), NOW));
    }

    @Test
    void aClaimThatDoesNotMatchTheSignedAttestationIsSignatureInvalid() {
        // the signature is over SECURE_ELEMENT_OR_TPM; claiming TEE changes the canonical -> the sig won't verify
        DeviceIdentityVerifier v = new DeviceIdentityVerifier(anchors, DeviceProtectionLevel.TEE);
        assertEquals(Verdict.SIGNATURE_INVALID,
                v.verify(att(DeviceProtectionLevel.TEE, true, sigSeTpm, chain), NOW));
        // a corrupted signature also fails
        byte[] corrupt = sigSeTpm.clone();
        corrupt[corrupt.length - 1] ^= 0x01;
        assertEquals(Verdict.SIGNATURE_INVALID,
                v.verify(att(DeviceProtectionLevel.SECURE_ELEMENT_OR_TPM, true, corrupt, chain), NOW));
    }

    @Test
    void aChainThatDoesNotBuildToAConfiguredDeviceRootIsUntrusted() {
        // no anchors -> trust nothing
        DeviceIdentityVerifier noRoots = new DeviceIdentityVerifier(Set.of(), DeviceProtectionLevel.TEE);
        assertEquals(Verdict.UNTRUSTED_CHAIN,
                noRoots.verify(att(DeviceProtectionLevel.SECURE_ELEMENT_OR_TPM, true, sigSeTpm, chain), NOW));
    }

    @Test
    void aMalformedChainIsMalformed() {
        DeviceIdentityVerifier v = new DeviceIdentityVerifier(anchors, DeviceProtectionLevel.TEE);
        assertEquals(Verdict.MALFORMED,
                v.verify(att(DeviceProtectionLevel.SECURE_ELEMENT_OR_TPM, true, sigSeTpm, List.of(new byte[]{1, 2, 3})), NOW));
    }

    @Test
    void incompleteOrNullEvidenceIsMissing() {
        DeviceIdentityVerifier v = new DeviceIdentityVerifier(anchors, DeviceProtectionLevel.TEE);
        assertEquals(Verdict.MISSING, v.verify(null, NOW));
        assertEquals(Verdict.MISSING,
                v.verify(att(DeviceProtectionLevel.SECURE_ELEMENT_OR_TPM, true, sigSeTpm, chain), null));
        // empty chain -> incomplete
        assertEquals(Verdict.MISSING,
                v.verify(att(DeviceProtectionLevel.SECURE_ELEMENT_OR_TPM, true, sigSeTpm, List.of()), NOW));
        // blank algorithm -> incomplete
        assertEquals(Verdict.MISSING, v.verify(
                new DeviceKeyAttestation(deviceKey, DeviceProtectionLevel.SECURE_ELEMENT_OR_TPM, true, sigSeTpm, "  ", chain), NOW));
    }

    @Test
    void aStrongerRequirementRejectsAMerelyTeeKey() {
        // require the strongest (TPM/SE); a SOFTWARE attestation is still WEAK (and TEE would be too)
        DeviceIdentityVerifier strict = new DeviceIdentityVerifier(anchors, DeviceProtectionLevel.SECURE_ELEMENT_OR_TPM);
        assertEquals(Verdict.TRUSTED,
                strict.verify(att(DeviceProtectionLevel.SECURE_ELEMENT_OR_TPM, true, sigSeTpm, chain), NOW));
        assertEquals(Verdict.WEAK_PROTECTION,
                strict.verify(att(DeviceProtectionLevel.SOFTWARE, true, sigSoftware, chain), NOW));
    }

    @Test
    void protectionLevelIsOrderedByRankAndVerdictTrustIsTrustedOnly() {
        assertTrue(DeviceProtectionLevel.SECURE_ELEMENT_OR_TPM.meetsRequired(DeviceProtectionLevel.TEE));
        assertFalse(DeviceProtectionLevel.SOFTWARE.meetsRequired(DeviceProtectionLevel.TEE));
        assertTrue(DeviceProtectionLevel.SECURE_ELEMENT_OR_TPM.rank() > DeviceProtectionLevel.SOFTWARE.rank());
        for (Verdict verdict : Verdict.values()) {
            assertEquals(verdict == Verdict.TRUSTED, verdict.isTrusted(), verdict.name());
        }
    }

    @Test
    void constructionRequiresAProtectionLevel() {
        assertThrows(IllegalArgumentException.class, () -> new DeviceIdentityVerifier(anchors, null));
    }
}

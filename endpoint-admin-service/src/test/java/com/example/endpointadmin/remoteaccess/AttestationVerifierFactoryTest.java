package com.example.endpointadmin.remoteaccess;

import com.example.endpointadmin.remoteaccess.AttestationVerifierFactory.VerifierType;
import org.junit.jupiter.api.Test;

import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 B1.4c-3 — {@link AttestationVerifierFactory} selection + the blocking matrix (fail-fast at
 * construction = startup), mirroring the cert-trust factory.
 */
class AttestationVerifierFactoryTest {

    private static final String BUILDER = "trusted-builder@slsa";
    private static final String POLICY = "expected-slsa-policy-hash";
    private static final String ALG = "SHA256withECDSA";

    private static PublicKey ecPublicKey() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(new ECGenParameterSpec("secp256r1"));
            return kpg.generateKeyPair().getPublic();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void unconfiguredBuilderOrPolicyYieldsNull() {
        assertNull(AttestationVerifierFactory.create(VerifierType.IN_MEMORY, "", POLICY, null, ALG, false));
        assertNull(AttestationVerifierFactory.create(VerifierType.KEY_BASED, BUILDER, "  ", ecPublicKey(), ALG, false));
    }

    @Test
    void inMemoryDefaultSelectsThePlaceholderInNonProd() {
        var v = AttestationVerifierFactory.create(null, BUILDER, POLICY, null, ALG, false);
        assertInstanceOf(InMemoryAttestationVerifier.class, v);
    }

    @Test
    void inMemoryPlaceholderIsForbiddenInAProductionLikeProfile() {
        var ex = assertThrows(IllegalStateException.class, () ->
                AttestationVerifierFactory.create(VerifierType.IN_MEMORY, BUILDER, POLICY, null, ALG, true));
        assertTrue(ex.getMessage().contains("PLACEHOLDER"), ex.getMessage());
    }

    @Test
    void keyBasedWithAConfiguredKeyBuildsTheRealVerifierEvenInProd() {
        var v = AttestationVerifierFactory.create(VerifierType.KEY_BASED, BUILDER, POLICY, ecPublicKey(), ALG, true);
        assertInstanceOf(KeyBasedAttestationVerifier.class, v);
    }

    @Test
    void keyBasedWithoutAKeyFailsFast() {
        var ex = assertThrows(IllegalStateException.class, () ->
                AttestationVerifierFactory.create(VerifierType.KEY_BASED, BUILDER, POLICY, null, ALG, false));
        assertTrue(ex.getMessage().contains("requires a configured public key"), ex.getMessage());
    }

    @Test
    void dsseIsRefusedUntilTheTransportWiresTheEnvelope() {
        var ex = assertThrows(IllegalStateException.class, () ->
                AttestationVerifierFactory.create(VerifierType.DSSE, BUILDER, POLICY, ecPublicKey(), ALG, false));
        assertTrue(ex.getMessage().contains("not yet selectable"), ex.getMessage());
    }
}

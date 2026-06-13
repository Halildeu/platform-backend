package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.bridge.orchestrator.SessionDeviceTrustVerifierFactory.VerifierType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Faz 22.6 D10.1 slice-3b (#634, Codex 019ec29a) — the {@link SessionDeviceTrustVerifierFactory} blocking matrix:
 * FAIL_CLOSED (and the default) is the deny-all verifier, allowed in every profile; MACHINE_CERT_ENROLLMENT is the
 * non-prod pilot verifier, refused fail-fast in a prod-like profile, and requires a resolver.
 */
class SessionDeviceTrustVerifierFactoryTest {

    private static ConnectedDeviceResolver resolver() {
        return mock(ConnectedDeviceResolver.class);
    }

    @Test
    void nullAndFailClosedYieldTheDenyAllVerifierInEveryProfile() {
        assertSame(DenyAllSessionDeviceTrustVerifier.INSTANCE,
                SessionDeviceTrustVerifierFactory.create((VerifierType) null, false, resolver()));
        assertSame(DenyAllSessionDeviceTrustVerifier.INSTANCE,
                SessionDeviceTrustVerifierFactory.create(VerifierType.FAIL_CLOSED, false, resolver()));
        // the deny-all default is allowed even in prod — it establishes no device trust, so it cannot widen it
        assertSame(DenyAllSessionDeviceTrustVerifier.INSTANCE,
                SessionDeviceTrustVerifierFactory.create(VerifierType.FAIL_CLOSED, true, null));
    }

    @Test
    void machineCertEnrollmentIsTheRealVerifierInNonProd() {
        assertInstanceOf(MachineCertEnrollmentDeviceTrustVerifier.class,
                SessionDeviceTrustVerifierFactory.create(VerifierType.MACHINE_CERT_ENROLLMENT, false, resolver()));
    }

    @Test
    void machineCertEnrollmentIsRefusedInAProdLikeProfile() {
        IllegalStateException rejected = assertThrows(IllegalStateException.class, () ->
                SessionDeviceTrustVerifierFactory.create(VerifierType.MACHINE_CERT_ENROLLMENT, true, resolver()));
        assertTrue(rejected.getMessage().contains("MACHINE_CERT_ENROLLMENT"), "the rejection names the type");
    }

    @Test
    void machineCertEnrollmentRequiresAResolver() {
        assertThrows(IllegalStateException.class, () ->
                SessionDeviceTrustVerifierFactory.create(VerifierType.MACHINE_CERT_ENROLLMENT, false, null));
    }

    @Test
    void theConfigStringEntryPointIsCaseAndSpaceInsensitiveAndFailClosed() {
        assertSame(DenyAllSessionDeviceTrustVerifier.INSTANCE,
                SessionDeviceTrustVerifierFactory.create("", false, resolver()));
        assertSame(DenyAllSessionDeviceTrustVerifier.INSTANCE,
                SessionDeviceTrustVerifierFactory.create((String) null, false, resolver()));
        assertSame(DenyAllSessionDeviceTrustVerifier.INSTANCE,
                SessionDeviceTrustVerifierFactory.create("  fail_closed  ", true, null));
        assertInstanceOf(MachineCertEnrollmentDeviceTrustVerifier.class,
                SessionDeviceTrustVerifierFactory.create("machine_cert_enrollment", false, resolver()));
        // an unknown config value is rejected fail-fast (not silently defaulted, not fail-open)
        assertThrows(IllegalStateException.class,
                () -> SessionDeviceTrustVerifierFactory.create("BOGUS", false, resolver()));
        // the prod-forbid still applies through the string entry point
        assertThrows(IllegalStateException.class,
                () -> SessionDeviceTrustVerifierFactory.create("machine_cert_enrollment", true, resolver()));
    }
}

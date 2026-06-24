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
 * non-prod pilot verifier; DEVICE_KEY_ATTESTATION is the non-prod STATIC CA device-key path (#732); and
 * REQUIRE_ENROLLMENT_AND_DEVICE_KEY composes enrollment with that static CA path. Reconciliation (#548, Codex
 * 019efada): the CA-static composite is allowed in NON-prod only and is REFUSED in a production-like profile —
 * production-grade hardware device trust must rest on the canonical TPM-native live challenge-response
 * ({@code DEVICE_KEY_ATTESTATION_REAL}, forthcoming), never on the non-live AgentHello-carried CA envelope.
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
    void deviceKeyAttestationIsTheNonProdHardwareOnlyVerifier() {
        assertSame(PeerDeviceKeyAttestationSessionDeviceTrustVerifier.INSTANCE,
                SessionDeviceTrustVerifierFactory.create(VerifierType.DEVICE_KEY_ATTESTATION, false, null));
    }

    @Test
    void deviceKeyAttestationAloneIsRefusedInAProdLikeProfile() {
        IllegalStateException rejected = assertThrows(IllegalStateException.class, () ->
                SessionDeviceTrustVerifierFactory.create(VerifierType.DEVICE_KEY_ATTESTATION, true, resolver()));
        assertTrue(rejected.getMessage().contains("DEVICE_KEY_ATTESTATION"), "the rejection names the type");
        assertTrue(rejected.getMessage().contains("DEVICE_KEY_ATTESTATION_REAL"),
                "the rejection points at the forthcoming canonical live verifier, not the CA-static composite");
    }

    @Test
    void compositeEnrollmentAndDeviceKeyVerifierRequiresAResolver() {
        assertThrows(IllegalStateException.class, () ->
                SessionDeviceTrustVerifierFactory.create(
                        VerifierType.REQUIRE_ENROLLMENT_AND_DEVICE_KEY, false, null));
    }

    @Test
    void compositeEnrollmentAndDeviceKeyVerifierIsAvailableInNonProd() {
        assertInstanceOf(CompositeSessionDeviceTrustVerifier.class,
                SessionDeviceTrustVerifierFactory.create(
                        VerifierType.REQUIRE_ENROLLMENT_AND_DEVICE_KEY, false, resolver()));
    }

    @Test
    void compositeEnrollmentAndDeviceKeyVerifierIsRefusedInAProdLikeProfile() {
        // its hardware leg promotes the STATIC CA device-key attestation (#732, non-live/replay-prone), NOT the
        // canonical #548 TPM-native live challenge-response — so the composite must not read as prod hardware trust
        IllegalStateException rejected = assertThrows(IllegalStateException.class, () ->
                SessionDeviceTrustVerifierFactory.create(
                        VerifierType.REQUIRE_ENROLLMENT_AND_DEVICE_KEY, true, resolver()));
        assertTrue(rejected.getMessage().contains("REQUIRE_ENROLLMENT_AND_DEVICE_KEY"),
                "the rejection names the type");
        assertTrue(rejected.getMessage().contains("DEVICE_KEY_ATTESTATION_REAL"),
                "the rejection points at the forthcoming canonical live verifier");
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
        assertSame(PeerDeviceKeyAttestationSessionDeviceTrustVerifier.INSTANCE,
                SessionDeviceTrustVerifierFactory.create("device_key_attestation", false, resolver()));
        assertInstanceOf(CompositeSessionDeviceTrustVerifier.class,
                SessionDeviceTrustVerifierFactory.create(
                        "require_enrollment_and_device_key", false, resolver()));
        // an unknown config value is rejected fail-fast (not silently defaulted, not fail-open)
        assertThrows(IllegalStateException.class,
                () -> SessionDeviceTrustVerifierFactory.create("BOGUS", false, resolver()));
        // the prod-forbid still applies through the string entry point — for EVERY non-FAIL_CLOSED basis, since
        // none of them is the canonical #548 TPM-native live verifier yet
        assertThrows(IllegalStateException.class,
                () -> SessionDeviceTrustVerifierFactory.create("machine_cert_enrollment", true, resolver()));
        assertThrows(IllegalStateException.class,
                () -> SessionDeviceTrustVerifierFactory.create("device_key_attestation", true, resolver()));
        assertThrows(IllegalStateException.class,
                () -> SessionDeviceTrustVerifierFactory.create("require_enrollment_and_device_key", true, resolver()));
    }
}

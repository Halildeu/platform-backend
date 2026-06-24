package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Faz 22.6 D10.1 slice-3b (#634, Codex 019ec29a) — selects the {@link SessionDeviceTrustVerifier} with a blocking
 * matrix at construction (= bean creation = STARTUP fail-fast), mirroring the slice-3a parser factory:
 * <ul>
 *   <li><b>FAIL_CLOSED</b> — {@link DenyAllSessionDeviceTrustVerifier}: device trust never established
 *       ({@code deviceTrusted=false}) → the broker stays gated. The default until a pilot opts in.</li>
 *   <li><b>MACHINE_CERT_ENROLLMENT</b> — {@link MachineCertEnrollmentDeviceTrustVerifier}: the session peer is the
 *       active enrolled machine cert for the tenant/device. FORBIDDEN in a production-like profile — this is
 *       enrollment identity, NOT hardware key attestation; prod device trust needs the policy/D29-EA to explicitly
 *       accept this basis.</li>
 *   <li><b>DEVICE_KEY_ATTESTATION</b> — {@link PeerDeviceKeyAttestationSessionDeviceTrustVerifier}: promotes the
 *       peer-trust ledger's STATIC CA device-key attestation (#732), parsed from the AgentHello-carried
 *       attestation envelope and verified by {@code DeviceIdentityVerifier} against a device-attestation root.
 *       This is a NON-LIVE, replay-prone basis (no broker nonce, no session liveness) — auxiliary, NOT #548
 *       closure. FORBIDDEN in a production-like profile by itself (hardware binding without tenant/device
 *       enrollment binding, and not a live proof).</li>
 *   <li><b>REQUIRE_ENROLLMENT_AND_DEVICE_KEY</b> — {@link CompositeSessionDeviceTrustVerifier}: composes active
 *       machine-cert enrollment with the STATIC CA device-key attestation above. Allowed in NON-prod only;
 *       FORBIDDEN in a production-like profile until the canonical #548 TPM-native live challenge-response
 *       verifier ({@code DEVICE_KEY_ATTESTATION_REAL}, forthcoming) backs the composite's hardware leg — so the
 *       static CA path can never silently read as production-grade hardware device trust.</li>
 * </ul>
 *
 * <p><b>Canonical #548 (TPM-native) vs auxiliary (CA-static):</b> the strong-path #548 device-key session
 * attestation is the broker-nonced {@code DeviceKeyChallenge}/{@code DeviceKeyAttestationResponse} live
 * challenge-response (PR #741 wire-contract; the {@code DEVICE_KEY_ATTESTATION_REAL} verifier is forthcoming).
 * The CA-static path above (#732) is a separate, non-live evidence family kept for future multi-platform
 * (Android/Apple/MDM) CA attestation; it is quarantined to non-prod and is explicitly NOT #548 closure.
 */
public final class SessionDeviceTrustVerifierFactory {

    private static final Logger log = LoggerFactory.getLogger(SessionDeviceTrustVerifierFactory.class);

    public enum VerifierType {
        FAIL_CLOSED,
        MACHINE_CERT_ENROLLMENT,
        DEVICE_KEY_ATTESTATION,
        REQUIRE_ENROLLMENT_AND_DEVICE_KEY
    }

    private SessionDeviceTrustVerifierFactory() {
    }

    private static IllegalStateException reject(String message) {
        log.error("remote-access session device-trust verifier config REJECTED (fail-fast): {}", message);
        return new IllegalStateException(message);
    }

    /**
     * Config-string entry point (the {@code remote-bridge.device-trust.verifier} value): blank/unset → the safe
     * {@code FAIL_CLOSED} default; an unknown value is REJECTED fail-fast (never fail-open). Case/space-insensitive.
     */
    public static SessionDeviceTrustVerifier create(String configuredType, boolean productionLikeProfile,
                                                    ConnectedDeviceResolver resolver) {
        String raw = configuredType == null ? "" : configuredType.strip();
        VerifierType type;
        if (raw.isEmpty()) {
            type = VerifierType.FAIL_CLOSED; // unset config → the safe default
        } else {
            try {
                type = VerifierType.valueOf(raw.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                throw reject("unknown device-trust verifier type '" + raw
                        + "' (expected FAIL_CLOSED|MACHINE_CERT_ENROLLMENT|DEVICE_KEY_ATTESTATION|"
                        + "REQUIRE_ENROLLMENT_AND_DEVICE_KEY)");
            }
        }
        return create(type, productionLikeProfile, resolver);
    }

    /**
     * @param productionLikeProfile when true, the enrollment-only MACHINE_CERT_ENROLLMENT verifier is REFUSED
     * @throws IllegalStateException on any forbidden combination (fail-fast startup)
     */
    public static SessionDeviceTrustVerifier create(VerifierType type, boolean productionLikeProfile,
                                                    ConnectedDeviceResolver resolver) {
        VerifierType t = type == null ? VerifierType.FAIL_CLOSED : type; // fail-closed default
        switch (t) {
            case FAIL_CLOSED -> {
                return DenyAllSessionDeviceTrustVerifier.INSTANCE;
            }
            case MACHINE_CERT_ENROLLMENT -> {
                if (productionLikeProfile) {
                    throw reject("device-trust verifier MACHINE_CERT_ENROLLMENT is enrollment-only device trust "
                            + "(not hardware key attestation) and is forbidden in a production-like profile until "
                            + "the policy/D29-EA explicitly accepts this pilot basis");
                }
                if (resolver == null) {
                    throw reject("device-trust verifier MACHINE_CERT_ENROLLMENT requires a ConnectedDeviceResolver");
                }
                return new MachineCertEnrollmentDeviceTrustVerifier(resolver);
            }
            case DEVICE_KEY_ATTESTATION -> {
                if (productionLikeProfile) {
                    throw reject("device-trust verifier DEVICE_KEY_ATTESTATION verifies hardware-key evidence "
                            + "but does not bind that key to the requested tenant/device; use "
                            + "REQUIRE_ENROLLMENT_AND_DEVICE_KEY for production-shaped device trust");
                }
                return PeerDeviceKeyAttestationSessionDeviceTrustVerifier.INSTANCE;
            }
            case REQUIRE_ENROLLMENT_AND_DEVICE_KEY -> {
                if (resolver == null) {
                    throw reject("device-trust verifier REQUIRE_ENROLLMENT_AND_DEVICE_KEY requires a "
                            + "ConnectedDeviceResolver");
                }
                if (productionLikeProfile) {
                    throw reject("device-trust verifier REQUIRE_ENROLLMENT_AND_DEVICE_KEY composes machine-cert "
                            + "enrollment with the STATIC CA device-key attestation path (#732): its hardware leg "
                            + "promotes PeerTrust.deviceTrusted, derived from the AgentHello-carried CA attestation "
                            + "envelope — a non-live, replay-prone basis (no broker nonce, no session liveness), NOT "
                            + "the canonical #548 TPM-native live challenge-response. It is FORBIDDEN in a "
                            + "production-like profile until the live DEVICE_KEY_ATTESTATION_REAL verifier lands and "
                            + "backs the composite's hardware leg, so the static CA path can never silently read as "
                            + "production-grade hardware device trust");
                }
                return new CompositeSessionDeviceTrustVerifier(
                        new MachineCertEnrollmentDeviceTrustVerifier(resolver),
                        PeerDeviceKeyAttestationSessionDeviceTrustVerifier.INSTANCE);
            }
            default -> throw reject("unreachable device-trust verifier type " + t);
        }
    }
}

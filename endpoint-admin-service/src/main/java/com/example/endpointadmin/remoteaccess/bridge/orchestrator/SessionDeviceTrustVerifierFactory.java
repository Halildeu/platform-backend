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
 *       accept this basis (or the future {@code DeviceKeyAttestation} path).</li>
 * </ul>
 * Future modes ({@code DEVICE_KEY_ATTESTATION_REAL}, {@code REQUIRE_ENROLLMENT_AND_DEVICE_KEY}) arrive only once
 * the agent wire contract carries a real device-key attestation.
 */
public final class SessionDeviceTrustVerifierFactory {

    private static final Logger log = LoggerFactory.getLogger(SessionDeviceTrustVerifierFactory.class);

    public enum VerifierType { FAIL_CLOSED, MACHINE_CERT_ENROLLMENT }

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
                        + "' (expected FAIL_CLOSED|MACHINE_CERT_ENROLLMENT)");
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
            default -> throw reject("unreachable device-trust verifier type " + t);
        }
    }
}

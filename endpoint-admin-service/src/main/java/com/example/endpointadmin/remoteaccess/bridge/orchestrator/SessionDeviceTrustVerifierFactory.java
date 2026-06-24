package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.repository.EndpointTpmDeviceBindingRepository;
import com.example.endpointadmin.tpmattest.TpmEkChainValidator;
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
        REQUIRE_ENROLLMENT_AND_DEVICE_KEY,
        /**
         * The canonical #548 TPM-native live challenge-response hardware verifier
         * ({@link DeviceKeyAttestationRealSessionDeviceTrustVerifier}). The ONLY basis that is a genuine
         * {@link SessionDeviceTrustVerifier.Basis#HARDWARE_KEY_ATTESTATION} — ALLOWED in a production-like profile.
         */
        DEVICE_KEY_ATTESTATION_REAL,
        /**
         * Production posture: machine-cert enrollment AND the REAL TPM-native hardware verifier composed
         * ({@link SessionDeviceTrustVerifier.Basis#COMPOSITE}). Both must pass — defense-in-depth, ALLOWED in a
         * production-like profile. (Distinct from {@link #REQUIRE_ENROLLMENT_AND_DEVICE_KEY}, whose hardware leg is
         * the non-live CA-static path and is forbidden in prod.)
         */
        REQUIRE_ENROLLMENT_AND_DEVICE_KEY_REAL
    }

    /**
     * The extra dependencies the REAL TPM-native verifier needs ({@link DeviceKeyAttestationRealSessionDeviceTrustVerifier}):
     * the session evidence store the control plane populates, the persisted enrollment-binding repository (the
     * AK&harr;EK anchor), and the EK manufacturer-root chain validator. Null (or any null member) is the
     * "not wired" state — selecting a REAL verifier type without it is REJECTED fail-fast.
     */
    public record DeviceKeyRealVerifierDependencies(TpmDeviceKeySessionEvidenceStore evidenceStore,
                                                    EndpointTpmDeviceBindingRepository bindings,
                                                    TpmEkChainValidator ekChainValidator) {
        boolean isComplete() {
            return evidenceStore != null && bindings != null && ekChainValidator != null;
        }
    }

    private SessionDeviceTrustVerifierFactory() {
    }

    private static IllegalStateException reject(String message) {
        log.error("remote-access session device-trust verifier config REJECTED (fail-fast): {}", message);
        return new IllegalStateException(message);
    }

    /** Back-compat entry point (no REAL deps): a REAL verifier type selected here is rejected fail-fast. */
    public static SessionDeviceTrustVerifier create(String configuredType, boolean productionLikeProfile,
                                                    ConnectedDeviceResolver resolver) {
        return create(configuredType, productionLikeProfile, resolver, null);
    }

    /**
     * Config-string entry point (the {@code remote-bridge.device-trust.verifier} value): blank/unset → the safe
     * {@code FAIL_CLOSED} default; an unknown value is REJECTED fail-fast (never fail-open). Case/space-insensitive.
     */
    public static SessionDeviceTrustVerifier create(String configuredType, boolean productionLikeProfile,
                                                    ConnectedDeviceResolver resolver,
                                                    DeviceKeyRealVerifierDependencies realDeps) {
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
                        + "REQUIRE_ENROLLMENT_AND_DEVICE_KEY|DEVICE_KEY_ATTESTATION_REAL|"
                        + "REQUIRE_ENROLLMENT_AND_DEVICE_KEY_REAL)");
            }
        }
        return create(type, productionLikeProfile, resolver, realDeps);
    }

    /** Back-compat entry point (no REAL deps): a REAL verifier type selected here is rejected fail-fast. */
    public static SessionDeviceTrustVerifier create(VerifierType type, boolean productionLikeProfile,
                                                    ConnectedDeviceResolver resolver) {
        return create(type, productionLikeProfile, resolver, null);
    }

    /**
     * @param productionLikeProfile when true, the enrollment-only MACHINE_CERT_ENROLLMENT verifier + the
     *                              non-live CA-static paths are REFUSED; the REAL TPM-native verifier is allowed
     * @param realDeps              the dependencies the REAL TPM-native verifier needs; required (and complete)
     *                              only for {@code DEVICE_KEY_ATTESTATION_REAL} / {@code *_REAL} types
     * @throws IllegalStateException on any forbidden combination (fail-fast startup)
     */
    public static SessionDeviceTrustVerifier create(VerifierType type, boolean productionLikeProfile,
                                                    ConnectedDeviceResolver resolver,
                                                    DeviceKeyRealVerifierDependencies realDeps) {
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
                    throw reject("device-trust verifier DEVICE_KEY_ATTESTATION promotes the STATIC CA device-key "
                            + "attestation path (#732) carried in the AgentHello envelope — a non-live, "
                            + "replay-prone basis that also does not bind the key to the requested tenant/device. "
                            + "It is FORBIDDEN in a production-like profile; production-grade hardware device trust "
                            + "requires the canonical #548 TPM-native live challenge-response "
                            + "(DEVICE_KEY_ATTESTATION_REAL) backing the composite");
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
                            + "production-like profile; use REQUIRE_ENROLLMENT_AND_DEVICE_KEY_REAL, whose hardware "
                            + "leg is the live DEVICE_KEY_ATTESTATION_REAL verifier");
                }
                return new CompositeSessionDeviceTrustVerifier(
                        new MachineCertEnrollmentDeviceTrustVerifier(resolver),
                        PeerDeviceKeyAttestationSessionDeviceTrustVerifier.INSTANCE);
            }
            case DEVICE_KEY_ATTESTATION_REAL -> {
                // the canonical #548 strong path — a genuine HARDWARE_KEY_ATTESTATION; ALLOWED in prod
                return new DeviceKeyAttestationRealSessionDeviceTrustVerifier(
                        requireRealDeps(t, resolver, realDeps).evidenceStore(),
                        resolver, realDeps.bindings(), realDeps.ekChainValidator());
            }
            case REQUIRE_ENROLLMENT_AND_DEVICE_KEY_REAL -> {
                // production posture: machine-cert enrollment AND the live TPM-native hardware verifier (COMPOSITE)
                requireRealDeps(t, resolver, realDeps);
                return new CompositeSessionDeviceTrustVerifier(
                        new MachineCertEnrollmentDeviceTrustVerifier(resolver),
                        new DeviceKeyAttestationRealSessionDeviceTrustVerifier(
                                realDeps.evidenceStore(), resolver, realDeps.bindings(),
                                realDeps.ekChainValidator()));
            }
            default -> throw reject("unreachable device-trust verifier type " + t);
        }
    }

    /** Both REAL types need a resolver AND complete REAL deps — fail-fast at startup otherwise (never fail-open). */
    private static DeviceKeyRealVerifierDependencies requireRealDeps(VerifierType type,
            ConnectedDeviceResolver resolver, DeviceKeyRealVerifierDependencies realDeps) {
        if (resolver == null) {
            throw reject("device-trust verifier " + type + " requires a ConnectedDeviceResolver");
        }
        if (realDeps == null || !realDeps.isComplete()) {
            throw reject("device-trust verifier " + type + " requires the TPM-native dependencies "
                    + "(session evidence store + enrollment-binding repository + EK chain validator) — the "
                    + "EK chain validator needs endpoint-admin.tpm-attest.enabled=true with pinned manufacturer roots");
        }
        return realDeps;
    }
}

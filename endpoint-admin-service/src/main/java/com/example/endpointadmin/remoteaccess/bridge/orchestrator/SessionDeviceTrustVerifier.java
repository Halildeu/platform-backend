package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.bridge.orchestrator.PeerTrustLedger.PeerTrust;

/**
 * Faz 22.6 D10.1 slice-3b (#634, Codex 019ec29a) — decides a session's {@code deviceTrusted} from the SESSION
 * context (operator tenant + requested device + the live peer), not from agent-supplied fields alone. The broker
 * gates PERMIT on {@code deviceTrusted}; until this slice it could only be false (the parser produces no device-key
 * attestation), so this is the seam that turns it true on REAL evidence — without overclaiming hardware binding.
 *
 * <p><b>Honest naming (Codex):</b> a {@link Basis#MACHINE_CERT_ENROLLMENT} decision means "the session peer is the
 * active enrolled machine certificate for the requested tenant/device" — device <em>enrollment</em> identity, NOT
 * a TPM/secure-element <em>hardware key attestation</em> ({@link Basis#HARDWARE_KEY_ATTESTATION}, the future
 * {@code DeviceKeyAttestation} path). The decision carries its basis + reason so the distinction is auditable and
 * never silently conflated.
 *
 * <p>Total + fail-closed: a missing/ambiguous/erroring input is {@link DeviceTrustDecision#deny}, never a throw.
 */
public interface SessionDeviceTrustVerifier {

    /**
     * @param session        the live session (operator tenant + requested device + the authenticated peer key)
     * @param peerTrust      the per-peer ledger trust (may be null = no fresh peer evidence); used by future
     *                       composite modes that combine enrollment with hardware attestation
     * @param nowEpochMillis the evaluation instant (cert validity / freshness are evaluated at this time)
     * @return the device-trust decision — total, fail-closed (never throws)
     */
    DeviceTrustDecision verify(RemoteBridgeSession session, PeerTrust peerTrust, long nowEpochMillis);

    /** What a {@code deviceTrusted=true} actually rests on — so the broker/audit never conflate the bases. */
    enum Basis {
        /** No device trust established (the fail-closed default). */
        NONE,
        /** The session peer is the active enrolled machine cert for the tenant/device — enrollment identity. */
        MACHINE_CERT_ENROLLMENT,
        /** A verified TPM/secure-element device-key attestation — true hardware binding (future path). */
        HARDWARE_KEY_ATTESTATION,
        /** Enrollment AND hardware-key attestation together (future prod posture). */
        COMPOSITE
    }

    /** The explicit, auditable outcome of one session device-trust check. */
    record DeviceTrustDecision(boolean trusted, Basis basis, String reason) {

        public static DeviceTrustDecision deny(String reason) {
            return new DeviceTrustDecision(false, Basis.NONE, reason);
        }

        public static DeviceTrustDecision enrolledActive() {
            return new DeviceTrustDecision(true, Basis.MACHINE_CERT_ENROLLMENT, "enrolled-active-machine-cert");
        }

        public static DeviceTrustDecision hardwareKeyAttested() {
            return new DeviceTrustDecision(true, Basis.HARDWARE_KEY_ATTESTATION,
                    "hardware-key-attestation-verified");
        }

        public static DeviceTrustDecision enrollmentAndHardwareKeyAttested() {
            return new DeviceTrustDecision(true, Basis.COMPOSITE,
                    "enrollment-and-hardware-key-attested");
        }
    }
}

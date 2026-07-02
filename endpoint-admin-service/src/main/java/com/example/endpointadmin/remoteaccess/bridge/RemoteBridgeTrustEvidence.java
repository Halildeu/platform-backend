package com.example.endpointadmin.remoteaccess.bridge;

import com.example.endpointadmin.remoteaccess.DuressResponsePolicy;
import com.example.endpointadmin.remoteaccess.OperatorStepUpPolicy;
import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;
import com.example.endpointadmin.remoteaccess.bridge.contract.ConsentLease;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.SessionDeviceTrustVerifier.Basis;

import java.util.Set;

/**
 * Faz 22.6 T-1b — the broker's per-session AUTHORITATIVE trust + identity bundle (Codex 019eb9fb). Every field
 * is BROKER-PRODUCED — the cert/attestation/device results come from running the B1.4 verifiers, the step-up
 * from the WebAuthn assertion verifier, the duress signal from the duress detector, the granted capabilities
 * from the owner-signed pilot token, the consent lease from the endpoint user's {@code ConsentResult}, and the
 * identity from the established session. It is NEVER built from {@link RemoteBridgeMessages.AgentHello} (which
 * is advisory only): a compromised/lying agent cannot self-assert trust by populating this object — production
 * code constructs it solely from the verifiers, tests construct it directly.
 *
 * <p>This is the broker's input to {@code RemoteSessionPolicyEngine.evaluate}; it deliberately mirrors the
 * engine's {@code SessionContext} crypto inputs (the verifier results) so the broker is a thin, config-free
 * composition layer.
 */
public record RemoteBridgeTrustEvidence(boolean certTrusted,
                                        boolean attestationVerified,
                                        boolean deviceTrusted,
                                        Basis deviceTrustBasis,
                                        boolean deviceTrustDecisionTrusted,
                                        Basis deviceTrustDecisionBasis,
                                        String deviceTrustDecisionReason,
                                        boolean deviceTrustIdentitiesConsistent,
                                        String cryptoIdentityDetail,
                                        OperatorStepUpPolicy.StepUpState stepUpState,
                                        DuressResponsePolicy.DuressSignal duressSignal,
                                        Set<RemoteSessionCapability> grantedCapabilities,
                                        ConsentLease consentLease,
                                        String deviceId,
                                        String operatorSubject) {

    public RemoteBridgeTrustEvidence {
        grantedCapabilities = grantedCapabilities == null ? Set.of() : Set.copyOf(grantedCapabilities);
        deviceTrustBasis = deviceTrustBasis == null ? Basis.NONE : deviceTrustBasis;
        deviceTrustDecisionBasis = deviceTrustDecisionBasis == null ? Basis.NONE : deviceTrustDecisionBasis;
    }

    public RemoteBridgeTrustEvidence(boolean certTrusted,
                                     boolean attestationVerified,
                                     boolean deviceTrusted,
                                     OperatorStepUpPolicy.StepUpState stepUpState,
                                     DuressResponsePolicy.DuressSignal duressSignal,
                                     Set<RemoteSessionCapability> grantedCapabilities,
                                     ConsentLease consentLease,
                                     String deviceId,
                                     String operatorSubject) {
        this(certTrusted, attestationVerified, deviceTrusted, Basis.NONE, null, stepUpState, duressSignal,
                grantedCapabilities, consentLease, deviceId, operatorSubject);
    }

    public RemoteBridgeTrustEvidence(boolean certTrusted,
                                     boolean attestationVerified,
                                     boolean deviceTrusted,
                                     String cryptoIdentityDetail,
                                     OperatorStepUpPolicy.StepUpState stepUpState,
                                     DuressResponsePolicy.DuressSignal duressSignal,
                                     Set<RemoteSessionCapability> grantedCapabilities,
                                     ConsentLease consentLease,
                                     String deviceId,
                                     String operatorSubject) {
        this(certTrusted, attestationVerified, deviceTrusted, Basis.NONE, cryptoIdentityDetail, stepUpState,
                duressSignal, grantedCapabilities, consentLease, deviceId, operatorSubject);
    }

    public RemoteBridgeTrustEvidence(boolean certTrusted,
                                     boolean attestationVerified,
                                     boolean deviceTrusted,
                                     Basis deviceTrustBasis,
                                     String cryptoIdentityDetail,
                                     OperatorStepUpPolicy.StepUpState stepUpState,
                                     DuressResponsePolicy.DuressSignal duressSignal,
                                     Set<RemoteSessionCapability> grantedCapabilities,
                                     ConsentLease consentLease,
                                     String deviceId,
                                     String operatorSubject) {
        this(certTrusted, attestationVerified, deviceTrusted, deviceTrustBasis, deviceTrusted,
                deviceTrustBasis, defaultDeviceTrustDecisionReason(deviceTrusted, deviceTrustBasis,
                        cryptoIdentityDetail), deviceTrusted, cryptoIdentityDetail, stepUpState, duressSignal,
                grantedCapabilities, consentLease, deviceId, operatorSubject);
    }

    private static String defaultDeviceTrustDecisionReason(boolean trusted, Basis basis, String detail) {
        if (!trusted) {
            return detail == null || detail.isBlank() ? "device-untrusted" : detail;
        }
        return switch (basis == null ? Basis.NONE : basis) {
            case MACHINE_CERT_ENROLLMENT -> "enrolled-active-machine-cert";
            case HARDWARE_KEY_ATTESTATION -> "hardware-key-attestation-verified";
            case COMPOSITE -> "enrollment-and-hardware-key-attested";
            case NONE -> "device-trust-verified";
        };
    }
}

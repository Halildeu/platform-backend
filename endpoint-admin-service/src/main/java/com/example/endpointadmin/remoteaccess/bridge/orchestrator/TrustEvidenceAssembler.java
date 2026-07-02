package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.DuressResponsePolicy.DuressSignal;
import com.example.endpointadmin.remoteaccess.OperatorStepUpPolicy.StepUpState;
import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeTrustEvidence;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.PeerTrustLedger.PeerTrust;

import java.util.Objects;
import java.util.Set;

/**
 * Faz 22.6 T-4a-ii slice-4b-1 (Codex 019ebd7f) — assembles the {@link RemoteBridgeTrustEvidence} the broker
 * needs from the live session + the per-peer trust ledger + the owner-token gate. Pure/total/FAIL-CLOSED: the
 * absence of evidence is never read as trust.
 *
 * <ul>
 *   <li><b>cert/attestation:</b> from {@link PeerTrustLedger#fresh} — a missing/stale peer trust ⇒ both FALSE
 *       (no fresh verifier evidence ⇒ untrusted).</li>
 *   <li><b>device:</b> from the injected {@link SessionDeviceTrustVerifier} (D10.1 slice-3b) — e.g. the
 *       enrolled-active machine-cert basis — NOT the ledger's (future) hardware-attestation {@code deviceTrusted}.
 *       It is ANDed with transport identity consistency: a missing peer trust (null), or a certificate-bound device
 *       id that disagrees with the session's device id, ⇒ {@code deviceTrusted=false} (fail-closed). Advisory
 *       {@code AgentHello.deviceId} is deliberately not authoritative; the enrolled certificate + connected peer
 *       resolver is the binding source.</li>
 *   <li><b>granted capabilities:</b> {@link OwnerTokenGate#effectiveGrant}(owner-token grant ∩ session
 *       request ∩ pilot allowlist) — owner-authoritative, request-narrowing, pilot-bounded.</li>
 *   <li><b>duress:</b> from an injected {@link DuressSignalSource}. Until the transport duress-classification
 *       path is wired, the absence of a source is NOT {@code NONE} ("no duress") but {@code AMBIGUOUS}
 *       ("cannot tell") — the broker maps {@code AMBIGUOUS} to a KILL, so the unwired default is fail-closed
 *       (Codex S1).</li>
 *   <li><b>step-up:</b> the {@link StepUpState} READ FROM THE SESSION (lastStepUp + strength), which the
 *       operator transport advances on a VERIFIED WebAuthn step-up (slice-4c). Until a step-up is recorded the
 *       session stays at the fail-closed weakest (lastStepUp=0, NONE), so the policy sees no satisfied step-up
 *       and routes high-risk operations to {@code REQUIRE_STEP_UP}/deny (Codex S2).</li>
 * </ul>
 */
public final class TrustEvidenceAssembler {

    /**
     * Classifies the duress signal for a session. The absence of a real source is {@code AMBIGUOUS}
     * (fail-closed), never {@code NONE} — only an explicit source that says "clean" yields {@code NONE}.
     */
    @FunctionalInterface
    public interface DuressSignalSource {
        DuressSignal classify(String sessionId, long nowEpochMillis);

        /** No transport duress-classification wired yet ⇒ AMBIGUOUS (→ broker kill) — fail-closed. */
        DuressSignalSource AMBIGUOUS_UNTIL_WIRED = (sessionId, nowEpochMillis) -> DuressSignal.AMBIGUOUS;
    }

    private final PeerTrustLedger ledger;
    private final OwnerTokenGate ownerGate;
    private final SessionDeviceTrustVerifier deviceTrustVerifier;
    private final DuressSignalSource duressSource;

    /**
     * Canonical wiring (Faz 22.6 D10.1 slice-3b): {@code deviceTrustVerifier} decides device trust from the
     * session context (the enrolled-active machine-cert basis). A null verifier is the unwired state →
     * {@link DenyAllSessionDeviceTrustVerifier} (device trust never established, fail-closed), never an NPE.
     */
    public TrustEvidenceAssembler(PeerTrustLedger ledger, OwnerTokenGate ownerGate,
                                  SessionDeviceTrustVerifier deviceTrustVerifier,
                                  DuressSignalSource duressSource) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.ownerGate = Objects.requireNonNull(ownerGate, "ownerGate");
        this.deviceTrustVerifier = deviceTrustVerifier == null
                ? DenyAllSessionDeviceTrustVerifier.INSTANCE : deviceTrustVerifier;
        // a null source is the unwired state → fail-closed AMBIGUOUS, never a silent NONE
        this.duressSource = duressSource == null ? DuressSignalSource.AMBIGUOUS_UNTIL_WIRED : duressSource;
    }

    /**
     * Back-compat / no-device-trust construction: NO device-trust verifier wired ⇒ device trust is never
     * established ({@link DenyAllSessionDeviceTrustVerifier}, fail-closed). The pilot wires the 4-arg ctor.
     */
    public TrustEvidenceAssembler(PeerTrustLedger ledger, OwnerTokenGate ownerGate,
                                  DuressSignalSource duressSource) {
        this(ledger, ownerGate, DenyAllSessionDeviceTrustVerifier.INSTANCE, duressSource);
    }

    public RemoteBridgeTrustEvidence assemble(RemoteBridgeSession session, long nowEpochMillis) {
        Objects.requireNonNull(session, "session");
        PeerTrust trust = ledger.fresh(session.transportPeerKey(), nowEpochMillis).orElse(null);

        boolean certTrusted = trust != null && trust.certTrusted();
        boolean attestationVerified = trust != null && trust.attestationVerified();
        // device trust now comes from the session device-trust verifier (slice-3b): the enrolled-active machine
        // cert basis, NOT the ledger's (pilot-empty) hardware-attestation deviceTrusted. Transport consistency
        // still ANDs in: a missing peer trust (null), or a cert-bound device id that contradicts the session,
        // voids trust. AgentHello.deviceId is advisory and cannot veto the certificate/enrollment binding.
        // The decision's basis is auditable + honest (enrollment != hardware key attestation).
        SessionDeviceTrustVerifier.DeviceTrustDecision deviceDecision =
                deviceTrustVerifier.verify(session, trust, nowEpochMillis);
        boolean identitiesConsistent = deviceIdentitiesConsistent(trust, session.deviceId());
        boolean deviceDecisionTrusted = deviceDecision != null && deviceDecision.trusted();
        SessionDeviceTrustVerifier.Basis deviceDecisionBasis = deviceDecision == null || deviceDecision.basis() == null
                ? SessionDeviceTrustVerifier.Basis.NONE : deviceDecision.basis();
        String deviceDecisionReason = safeDetail(deviceDecision == null ? null : deviceDecision.reason(),
                deviceDecisionTrusted ? "device-trust-verified" : "device-untrusted");
        boolean deviceTrusted = deviceDecisionTrusted && identitiesConsistent;
        SessionDeviceTrustVerifier.Basis deviceTrustBasis = deviceTrusted
                ? deviceDecision.basis() : SessionDeviceTrustVerifier.Basis.NONE;
        String cryptoIdentityDetail = cryptoIdentityDetail(certTrusted, attestationVerified, deviceDecision,
                identitiesConsistent);

        Set<RemoteSessionCapability> granted = OwnerTokenGate.effectiveGrant(
                ownerGate.grantedCapabilities(new OwnerTokenGate.OwnerGrantContext(session.sessionId(),
                        session.operatorTenantId(), session.operatorSubject(), session.sessionStartEpochMillis()),
                        nowEpochMillis),
                session.requestedCapabilities());

        DuressSignal duress = classifyDuress(session.sessionId(), nowEpochMillis);
        // step-up freshness/strength is now read from the session (Faz 22.6 D step-up wiring) — the operator
        // transport records a VERIFIED step-up into it (slice-4c); until then it stays the fail-closed weakest
        // (lastStepUp=0, NONE), so the policy still routes high-risk operations to REQUIRE_STEP_UP unchanged.
        StepUpState stepUp = new StepUpState(session.lastStepUpEpochMillis(),
                session.sessionStartEpochMillis(), session.stepUpStrength());

        return new RemoteBridgeTrustEvidence(certTrusted, attestationVerified, deviceTrusted, deviceTrustBasis,
                deviceDecisionTrusted, deviceDecisionBasis, deviceDecisionReason, identitiesConsistent,
                cryptoIdentityDetail, stepUp, duress, granted, session.lease(), session.deviceId(),
                session.operatorSubject());
    }

    private DuressSignal classifyDuress(String sessionId, long nowEpochMillis) {
        DuressSignal signal = duressSource.classify(sessionId, nowEpochMillis);
        return signal == null ? DuressSignal.AMBIGUOUS : signal; // a null classification is fail-closed
    }

    /**
     * Transport-bound device identity must agree with the session's device id. A present certificate-bound device
     * id that disagrees voids device trust. AgentHello.deviceId is advisory-only and is intentionally ignored here:
     * the load-bearing binding is {@code session device -> active machine cert thumbprint -> connected peer}.
     */
    static boolean deviceIdentitiesConsistent(PeerTrust trust, String sessionDeviceId) {
        if (trust == null || sessionDeviceId == null || sessionDeviceId.isBlank()) {
            return false; // no peer-trust evidence (or no session device id) cannot confirm device consistency
        }
        return trust.certBoundDeviceId().map(sessionDeviceId::equals).orElse(true);
    }

    private static String cryptoIdentityDetail(boolean certTrusted,
                                               boolean attestationVerified,
                                               SessionDeviceTrustVerifier.DeviceTrustDecision deviceDecision,
                                               boolean identitiesConsistent) {
        if (!certTrusted) {
            return "cert-untrusted";
        }
        if (!attestationVerified) {
            return "attestation-unverified";
        }
        if (deviceDecision == null || !deviceDecision.trusted()) {
            return safeDetail(deviceDecision == null ? null : deviceDecision.reason(), "device-untrusted");
        }
        if (!identitiesConsistent) {
            return "device-identity-mismatch";
        }
        return null;
    }

    private static String safeDetail(String reason, String fallback) {
        if (reason == null || reason.isBlank()) {
            return fallback;
        }
        String canonical = reason.trim();
        return canonical.matches("^[a-z0-9-]{1,64}$") ? canonical : fallback;
    }
}

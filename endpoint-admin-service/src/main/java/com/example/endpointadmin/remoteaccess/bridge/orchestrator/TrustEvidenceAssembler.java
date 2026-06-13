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
 *   <li><b>trust (cert/attestation/device):</b> from {@link PeerTrustLedger#fresh} — a missing/stale peer
 *       trust ⇒ all three FALSE (no fresh verifier evidence ⇒ untrusted). Device trust additionally requires
 *       the ledger device identities to be CONSISTENT with the session's device id (a {@code helloDeviceId}
 *       or {@code certBoundDeviceId} that disagrees ⇒ {@code deviceTrusted=false}, Codex hardening).</li>
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
    private final DuressSignalSource duressSource;

    public TrustEvidenceAssembler(PeerTrustLedger ledger, OwnerTokenGate ownerGate,
                                  DuressSignalSource duressSource) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.ownerGate = Objects.requireNonNull(ownerGate, "ownerGate");
        // a null source is the unwired state → fail-closed AMBIGUOUS, never a silent NONE
        this.duressSource = duressSource == null ? DuressSignalSource.AMBIGUOUS_UNTIL_WIRED : duressSource;
    }

    public RemoteBridgeTrustEvidence assemble(RemoteBridgeSession session, long nowEpochMillis) {
        Objects.requireNonNull(session, "session");
        PeerTrust trust = ledger.fresh(session.transportPeerKey(), nowEpochMillis).orElse(null);

        boolean certTrusted = trust != null && trust.certTrusted();
        boolean attestationVerified = trust != null && trust.attestationVerified();
        boolean deviceTrusted = trust != null && trust.deviceTrusted()
                && deviceIdentitiesConsistent(trust, session.deviceId());

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

        return new RemoteBridgeTrustEvidence(certTrusted, attestationVerified, deviceTrusted, stepUp, duress,
                granted, session.lease(), session.deviceId(), session.operatorSubject());
    }

    private DuressSignal classifyDuress(String sessionId, long nowEpochMillis) {
        DuressSignal signal = duressSource.classify(sessionId, nowEpochMillis);
        return signal == null ? DuressSignal.AMBIGUOUS : signal; // a null classification is fail-closed
    }

    /**
     * The ledger device identities must agree with the session's device id: a {@code helloDeviceId} that
     * disagrees, or a present {@code certBoundDeviceId} that disagrees, voids device trust (a trusted peer
     * presenting a different device id is exactly the binding attack device trust must refuse).
     */
    static boolean deviceIdentitiesConsistent(PeerTrust trust, String sessionDeviceId) {
        if (sessionDeviceId == null || sessionDeviceId.isBlank()) {
            return false;
        }
        if (trust.helloDeviceId() != null && !trust.helloDeviceId().equals(sessionDeviceId)) {
            return false;
        }
        return trust.certBoundDeviceId().map(sessionDeviceId::equals).orElse(true);
    }
}

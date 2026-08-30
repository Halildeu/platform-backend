package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.AttestationEvidence;
import com.example.endpointadmin.remoteaccess.AttestationVerifier;
import com.example.endpointadmin.remoteaccess.CertRef;
import com.example.endpointadmin.remoteaccess.CertTrustEvaluator;
import com.example.endpointadmin.remoteaccess.DeviceIdentityVerifier;
import com.example.endpointadmin.remoteaccess.DuressResponsePolicy.DuressSignal;
import com.example.endpointadmin.remoteaccess.OperatorStepUpPolicy.MethodStrength;
import com.example.endpointadmin.remoteaccess.OperatorStepUpVerifier.StepUpVerification;
import com.example.endpointadmin.remoteaccess.OperatorStepUpVerifier.Verdict;
import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeSessionStateMachine.State;
import com.example.endpointadmin.remoteaccess.bridge.server.PeerIdentity;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeTrustEvidence;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.PeerTrustLedger.PeerTrust;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 T-4a-ii slice-4b-1 (Codex 019ebd7f) — the assembler is FAIL-CLOSED: a missing peer trust yields
 * all-false trust, the unwired duress source yields AMBIGUOUS (not NONE), and the grant is the owner ∩
 * request ∩ pilot intersection.
 */
class TrustEvidenceAssemblerTest {

    private static final long NOW = 1_000_000L;
    private static final long TTL = 30_000L;
    // a canonical operator-tenant UUID — mirrors the store-enforced canonical form (slice-4c-2b-2b)
    private static final String TENANT = "11111111-1111-1111-1111-111111111111";

    private static PeerEvidenceParser presentingParser() {
        return (peer, hello) -> new PeerEvidenceParser.ParsedEvidence(
                Optional.of(new CertRef("ab12", "SHA256withECDSA", "serial-1", "CN=agent-ca")),
                Optional.of(new AttestationEvidence("sha256:abc", "builder", "hash", "sig")),
                Optional.empty());
    }

    private static CertTrustEvaluator certResult(boolean valid) {
        return (cert, now) -> valid ? CertTrustEvaluator.TrustDecision.ALLOW
                : CertTrustEvaluator.TrustDecision.NOT_TRUSTED;
    }

    private static AttestationVerifier attestationResult(AttestationVerifier.AttestationDecision decision) {
        return (evidence, now) -> decision;
    }

    private static DeviceIdentityVerifier untrustingDeviceVerifier() {
        return new DeviceIdentityVerifier(Set.of(), DeviceIdentityVerifier.DeviceProtectionLevel.SECURE_ELEMENT_OR_TPM);
    }

    private PeerTrustLedger ledgerWith(boolean certValid, AttestationVerifier.AttestationDecision attestation) {
        return new PeerTrustLedger(certResult(certValid), attestationResult(attestation),
                untrustingDeviceVerifier(), presentingParser(), TTL);
    }

    private static PeerIdentity peer(String key) {
        return new PeerIdentity(key, Optional.of("dev-1"), java.util.List.of());
    }

    private static PeerIdentity peerWithoutCertBoundDevice(String key) {
        return new PeerIdentity(key, Optional.empty(), java.util.List.of());
    }

    private static com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.AgentHello hello() {
        return new com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.AgentHello(
                "0.2.3", "dev-1", "ab12", "ZXZpZGVuY2U=", "rb-v1", Set.of());
    }

    private static RemoteBridgeSession session(String sessionId, String peerKey, String deviceId,
                                               Set<RemoteSessionCapability> requested) {
        return new RemoteBridgeSession(sessionId, peerKey, deviceId, "operator@x", TENANT, "Operator X",
                requested, NOW + 60_000L, NOW, State.ACTIVE);
    }

    @Test
    void aMissingPeerTrustIsFailClosedAllFalse() {
        PeerTrustLedger ledger = ledgerWith(true, AttestationVerifier.AttestationDecision.VERIFIED); // no record
        TrustEvidenceAssembler assembler = new TrustEvidenceAssembler(ledger, OwnerTokenGate.DENY_ALL, null);

        RemoteBridgeTrustEvidence ev = assembler.assemble(
                session("s1", "peer-1", "dev-1", Set.of(RemoteSessionCapability.VIEW_ONLY)), NOW);

        assertFalse(ev.certTrusted());
        assertFalse(ev.attestationVerified());
        assertFalse(ev.deviceTrusted());
        assertEquals(SessionDeviceTrustVerifier.Basis.NONE, ev.deviceTrustBasis());
        assertTrue(ev.grantedCapabilities().isEmpty(), "DENY_ALL gate → no grant");
        assertEquals(DuressSignal.AMBIGUOUS, ev.duressSignal(), "unwired duress source → AMBIGUOUS, not NONE");
    }

    @Test
    void aFreshPeerTrustMapsCertAndAttestation() {
        PeerTrustLedger ledger = ledgerWith(true, AttestationVerifier.AttestationDecision.VERIFIED);
        ledger.record(peer("peer-1"), hello(), NOW); // now there IS a fresh trust for peer-1
        TrustEvidenceAssembler assembler = new TrustEvidenceAssembler(ledger, OwnerTokenGate.DENY_ALL, null);

        RemoteBridgeTrustEvidence ev = assembler.assemble(
                session("s1", "peer-1", "dev-1", Set.of(RemoteSessionCapability.VIEW_ONLY)), NOW);

        assertTrue(ev.certTrusted());
        assertTrue(ev.attestationVerified());
        assertFalse(ev.deviceTrusted(),
                "no device-trust verifier wired (3-arg ctor → deny-all) → deviceTrusted false");
    }

    // --- D10.1 slice-3b: device trust now comes from the session device-trust verifier ------------------

    @Test
    void anEnrollingDeviceTrustVerifierWithConsistentIdentitiesYieldsDeviceTrusted() {
        PeerTrustLedger ledger = ledgerWith(true, AttestationVerifier.AttestationDecision.VERIFIED);
        ledger.record(peer("peer-1"), hello(), NOW); // fresh trust; helloDeviceId == "dev-1"
        SessionDeviceTrustVerifier enrolled =
                (s, t, now) -> SessionDeviceTrustVerifier.DeviceTrustDecision.enrolledActive();
        TrustEvidenceAssembler assembler =
                new TrustEvidenceAssembler(ledger, OwnerTokenGate.DENY_ALL, enrolled, null);

        RemoteBridgeTrustEvidence ev = assembler.assemble(
                session("s1", "peer-1", "dev-1", Set.of(RemoteSessionCapability.VIEW_ONLY)), NOW);

        assertTrue(ev.deviceTrusted(), "enrolled-active verifier + consistent ledger device id → deviceTrusted");
        assertEquals(SessionDeviceTrustVerifier.Basis.MACHINE_CERT_ENROLLMENT, ev.deviceTrustBasis());
    }

    @Test
    void aHardwareKeyDeviceTrustVerifierYieldsHardwareBasisWhenIdentitiesAreConsistent() {
        PeerTrustLedger ledger = ledgerWith(true, AttestationVerifier.AttestationDecision.VERIFIED);
        ledger.record(peer("peer-1"), hello(), NOW); // fresh trust; cert-bound device id == "dev-1"
        SessionDeviceTrustVerifier hardware =
                (s, t, now) -> SessionDeviceTrustVerifier.DeviceTrustDecision.hardwareKeyAttested();
        TrustEvidenceAssembler assembler =
                new TrustEvidenceAssembler(ledger, OwnerTokenGate.DENY_ALL, hardware, null);

        RemoteBridgeTrustEvidence ev = assembler.assemble(
                session("s1", "peer-1", "dev-1", Set.of(RemoteSessionCapability.VIEW_ONLY)), NOW);

        assertTrue(ev.deviceTrusted());
        assertEquals(SessionDeviceTrustVerifier.Basis.HARDWARE_KEY_ATTESTATION, ev.deviceTrustBasis());
        assertTrue(ev.deviceTrustDecisionTrusted());
        assertEquals(SessionDeviceTrustVerifier.Basis.HARDWARE_KEY_ATTESTATION, ev.deviceTrustDecisionBasis());
        assertEquals("hardware-key-attestation-verified", ev.deviceTrustDecisionReason());
        assertTrue(ev.deviceTrustIdentitiesConsistent());
    }

    @Test
    void anEnrollingVerifierIsNotVoidedByAnAdvisoryHelloDeviceIdMismatch() {
        PeerTrustLedger ledger = ledgerWith(true, AttestationVerifier.AttestationDecision.VERIFIED);
        ledger.record(peerWithoutCertBoundDevice("peer-1"), hello(), NOW); // fresh trust; helloDeviceId == "dev-1"
        SessionDeviceTrustVerifier enrolled =
                (s, t, now) -> SessionDeviceTrustVerifier.DeviceTrustDecision.enrolledActive();
        TrustEvidenceAssembler assembler =
                new TrustEvidenceAssembler(ledger, OwnerTokenGate.DENY_ALL, enrolled, null);

        // AgentHello.deviceId is advisory; cert-bound identity is absent, so the enrollment verifier is
        // the load-bearing binding for this pilot path.
        RemoteBridgeTrustEvidence ev = assembler.assemble(
                session("s1", "peer-1", "dev-OTHER", Set.of(RemoteSessionCapability.VIEW_ONLY)), NOW);

        assertTrue(ev.deviceTrusted(), "advisory hello device id cannot veto active machine-cert enrollment trust");
        assertEquals(SessionDeviceTrustVerifier.Basis.MACHINE_CERT_ENROLLMENT, ev.deviceTrustBasis());
    }

    @Test
    void aDenyingDeviceTrustVerifierYieldsDeviceUntrusted() {
        PeerTrustLedger ledger = ledgerWith(true, AttestationVerifier.AttestationDecision.VERIFIED);
        ledger.record(peer("peer-1"), hello(), NOW); // fresh, consistent trust
        SessionDeviceTrustVerifier denying =
                (s, t, now) -> SessionDeviceTrustVerifier.DeviceTrustDecision.deny("not-enrolled");
        TrustEvidenceAssembler assembler =
                new TrustEvidenceAssembler(ledger, OwnerTokenGate.DENY_ALL, denying, null);

        RemoteBridgeTrustEvidence ev = assembler.assemble(
                session("s1", "peer-1", "dev-1", Set.of(RemoteSessionCapability.VIEW_ONLY)), NOW);

        assertFalse(ev.deviceTrusted(), "a denying device-trust verifier → deviceTrusted false");
        assertEquals(SessionDeviceTrustVerifier.Basis.NONE, ev.deviceTrustBasis());
        assertFalse(ev.deviceTrustDecisionTrusted());
        assertEquals(SessionDeviceTrustVerifier.Basis.NONE, ev.deviceTrustDecisionBasis());
        assertEquals("not-enrolled", ev.deviceTrustDecisionReason());
        assertTrue(ev.deviceTrustIdentitiesConsistent());
        assertEquals("not-enrolled", ev.cryptoIdentityDetail());
    }

    @Test
    void cryptoIdentityDetailKeepsDeviceVerifierReasonWhenAttestationIsAlsoMissing() {
        PeerTrustLedger ledger = ledgerWith(true, AttestationVerifier.AttestationDecision.MISSING);
        ledger.record(peer("peer-1"), hello(), NOW); // fresh, consistent cert trust; provenance not verified
        SessionDeviceTrustVerifier denying =
                (s, t, now) -> SessionDeviceTrustVerifier.DeviceTrustDecision.deny("no-fresh-device-key-evidence");
        TrustEvidenceAssembler assembler =
                new TrustEvidenceAssembler(ledger, OwnerTokenGate.DENY_ALL, denying, null);

        RemoteBridgeTrustEvidence ev = assembler.assemble(
                session("s1", "peer-1", "dev-1", Set.of(RemoteSessionCapability.VIEW_ONLY)), NOW);

        assertFalse(ev.attestationVerified());
        assertFalse(ev.deviceTrusted());
        assertEquals("no-fresh-device-key-evidence", ev.cryptoIdentityDetail());
    }

    @Test
    void aDeviceIdentityMismatchIsExposedAsBoundedCryptoIdentityDetail() {
        PeerTrustLedger ledger = ledgerWith(true, AttestationVerifier.AttestationDecision.VERIFIED);
        ledger.record(peer("peer-1"), hello(), NOW); // cert-bound device id = dev-1
        SessionDeviceTrustVerifier enrolled =
                (s, t, now) -> SessionDeviceTrustVerifier.DeviceTrustDecision.enrolledActive();
        TrustEvidenceAssembler assembler =
                new TrustEvidenceAssembler(ledger, OwnerTokenGate.DENY_ALL, enrolled, null);

        RemoteBridgeTrustEvidence ev = assembler.assemble(
                session("s1", "peer-1", "dev-OTHER", Set.of(RemoteSessionCapability.VIEW_ONLY)), NOW);

        assertFalse(ev.deviceTrusted());
        assertEquals(SessionDeviceTrustVerifier.Basis.NONE, ev.deviceTrustBasis());
        assertTrue(ev.deviceTrustDecisionTrusted());
        assertEquals(SessionDeviceTrustVerifier.Basis.MACHINE_CERT_ENROLLMENT, ev.deviceTrustDecisionBasis());
        assertEquals("enrolled-active-machine-cert", ev.deviceTrustDecisionReason());
        assertFalse(ev.deviceTrustIdentitiesConsistent());
        assertEquals("device-identity-mismatch", ev.cryptoIdentityDetail());
    }

    @Test
    void theGrantIsOwnerIntersectRequestIntersectPilot() {
        PeerTrustLedger ledger = ledgerWith(true, AttestationVerifier.AttestationDecision.VERIFIED);
        // owner token grants VIEW_ONLY + CONSTRAINED_PTY; the session requests only VIEW_ONLY
        OwnerTokenGate gate = (sessionId, operatorSubject) ->
                Set.of(RemoteSessionCapability.VIEW_ONLY, RemoteSessionCapability.CONSTRAINED_PTY);
        TrustEvidenceAssembler assembler = new TrustEvidenceAssembler(ledger, gate, null);

        RemoteBridgeTrustEvidence ev = assembler.assemble(
                session("s1", "peer-1", "dev-1", Set.of(RemoteSessionCapability.VIEW_ONLY)), NOW);

        assertEquals(Set.of(RemoteSessionCapability.VIEW_ONLY), ev.grantedCapabilities());
    }

    @Test
    void anExplicitCleanDuressSourceYieldsNone() {
        PeerTrustLedger ledger = ledgerWith(true, AttestationVerifier.AttestationDecision.VERIFIED);
        TrustEvidenceAssembler.DuressSignalSource clean = (sessionId, now) -> DuressSignal.NONE;
        TrustEvidenceAssembler assembler = new TrustEvidenceAssembler(ledger, OwnerTokenGate.DENY_ALL, clean);

        RemoteBridgeTrustEvidence ev = assembler.assemble(
                session("s1", "peer-1", "dev-1", Set.of(RemoteSessionCapability.VIEW_ONLY)), NOW);

        assertEquals(DuressSignal.NONE, ev.duressSignal());
    }

    @Test
    void aNullDuressClassificationIsFailClosedAmbiguous() {
        PeerTrustLedger ledger = ledgerWith(true, AttestationVerifier.AttestationDecision.VERIFIED);
        TrustEvidenceAssembler.DuressSignalSource nulling = (sessionId, now) -> null;
        TrustEvidenceAssembler assembler = new TrustEvidenceAssembler(ledger, OwnerTokenGate.DENY_ALL, nulling);

        RemoteBridgeTrustEvidence ev = assembler.assemble(
                session("s1", "peer-1", "dev-1", Set.of(RemoteSessionCapability.VIEW_ONLY)), NOW);

        assertEquals(DuressSignal.AMBIGUOUS, ev.duressSignal());
    }

    // --- device-identity consistency (Codex hardening) ----------------------

    @Test
    void deviceConsistencyHoldsWhenIdentitiesAgree() {
        PeerTrust trust = new PeerTrust(true, true, true, Optional.of("dev-1"), "dev-1", NOW);
        assertTrue(TrustEvidenceAssembler.deviceIdentitiesConsistent(trust, "dev-1"));
    }

    @Test
    void aHelloDeviceIdMismatchDoesNotVoidConsistency() {
        PeerTrust trust = new PeerTrust(true, true, true, Optional.empty(), "dev-OTHER", NOW);
        assertTrue(TrustEvidenceAssembler.deviceIdentitiesConsistent(trust, "dev-1"));
    }

    @Test
    void aCertBoundDeviceIdMismatchVoidsConsistency() {
        PeerTrust trust = new PeerTrust(true, true, true, Optional.of("dev-OTHER"), "dev-1", NOW);
        assertFalse(TrustEvidenceAssembler.deviceIdentitiesConsistent(trust, "dev-1"));
    }

    @Test
    void aBlankSessionDeviceIdVoidsConsistency() {
        PeerTrust trust = new PeerTrust(true, true, true, Optional.empty(), null, NOW);
        assertFalse(TrustEvidenceAssembler.deviceIdentitiesConsistent(trust, ""));
        assertFalse(TrustEvidenceAssembler.deviceIdentitiesConsistent(trust, null));
    }

    // --- D step-up wiring (the StepUpState is read from the session, not hardcoded) ---------------------

    @Test
    void theStepUpStateDefaultsToTheWeakestWithoutARecordedStepUp() {
        PeerTrustLedger ledger = ledgerWith(true, AttestationVerifier.AttestationDecision.VERIFIED);
        TrustEvidenceAssembler assembler = new TrustEvidenceAssembler(ledger, OwnerTokenGate.DENY_ALL, null);

        RemoteBridgeTrustEvidence ev = assembler.assemble(
                session("s1", "peer-1", "dev-1", Set.of(RemoteSessionCapability.VIEW_ONLY)), NOW);

        assertEquals(0L, ev.stepUpState().lastStepUpEpochMillis());
        assertEquals(MethodStrength.NONE, ev.stepUpState().methodStrength());
    }

    @Test
    void theStepUpStateReflectsAVerifiedSessionStepUp() {
        PeerTrustLedger ledger = ledgerWith(true, AttestationVerifier.AttestationDecision.VERIFIED);
        TrustEvidenceAssembler assembler = new TrustEvidenceAssembler(ledger, OwnerTokenGate.DENY_ALL, null);
        RemoteBridgeSession session = session("s1", "peer-1", "dev-1", Set.of(RemoteSessionCapability.VIEW_ONLY));
        session.recordStepUp(new StepUpVerification(Verdict.VERIFIED,
                MethodStrength.WEBAUTHN_USER_VERIFICATION, NOW + 100L));

        RemoteBridgeTrustEvidence ev = assembler.assemble(session, NOW + 200L);

        assertEquals(NOW + 100L, ev.stepUpState().lastStepUpEpochMillis());
        assertEquals(MethodStrength.WEBAUTHN_USER_VERIFICATION, ev.stepUpState().methodStrength());
    }
}

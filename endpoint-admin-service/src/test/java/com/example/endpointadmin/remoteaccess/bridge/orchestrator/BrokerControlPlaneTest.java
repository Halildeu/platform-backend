package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.AttestationEvidence;
import com.example.endpointadmin.remoteaccess.AttestationVerifier;
import com.example.endpointadmin.remoteaccess.CertRef;
import com.example.endpointadmin.remoteaccess.CertTrustEvaluator;
import com.example.endpointadmin.remoteaccess.DeviceIdentityVerifier;
import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeSessionStateMachine.Event;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeSessionStateMachine.State;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages;
import com.example.endpointadmin.remoteaccess.bridge.server.PeerIdentity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 T-4a-i (Codex 019ebbfa) — the inbound orchestration slice: peer trust is verifier-produced and
 * freshness-bound; a session's lifecycle is machine-driven only; consent binds to the broker's own prompt
 * window (the agent can shorten, never extend); LOCAL_ABORT always kills; nothing in this slice issues
 * authority (no prompts pushed, no permits — T-4a-ii).
 */
class BrokerControlPlaneTest {

    private static final PeerIdentity PEER = new PeerIdentity("peer-1", Optional.of("dev-1"), List.of());
    private static final PeerIdentity OTHER_PEER = new PeerIdentity("peer-2", Optional.empty(), List.of());
    private static final long NOW = 1_000_000L;
    private static final long PROMPT_EXPIRY = NOW + 60_000;

    // --- fakes -------------------------------------------------------------

    private static final CertRef CERT_REF = new CertRef("ab12", "SHA-256", "1", "CN=test-ca", List.of());

    private static PeerEvidenceParser presentingParser() {
        return (peer, hello) -> new PeerEvidenceParser.ParsedEvidence(
                Optional.of(CERT_REF),
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
        return new DeviceIdentityVerifier(java.util.Set.of(),
                DeviceIdentityVerifier.DeviceProtectionLevel.SECURE_ELEMENT_OR_TPM);
    }

    private record Recorded(String sessionId, String eventType) {
    }

    private static final class RecordingSinkStub
            implements com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeAuditSink {
        final ConcurrentLinkedQueue<Recorded> events = new ConcurrentLinkedQueue<>();
        volatile boolean failAll;

        @Override
        public void record(RemoteBridgeMessages.AuditEvent event) {
            if (failAll) {
                throw new IllegalStateException("durable sink down");
            }
            events.add(new Recorded(event.sessionId(), event.eventType()));
        }

        boolean has(String type) {
            return events.stream().anyMatch(r -> r.eventType().startsWith(type));
        }
    }

    private PeerTrustLedger ledger(PeerEvidenceParser parser, boolean certValid,
                                   AttestationVerifier.AttestationDecision attestation) {
        return new PeerTrustLedger(certResult(certValid), attestationResult(attestation),
                untrustingDeviceVerifier(), parser, 30_000);
    }

    private static RemoteBridgeMessages.AgentHello hello() {
        return new RemoteBridgeMessages.AgentHello("0.2.3", "dev-1", "ab12", "ZXZpZGVuY2U=", "rb-v1",
                Set.of());
    }

    private static RemoteBridgeMessages.SessionRequest request(String sessionId) {
        return new RemoteBridgeMessages.SessionRequest(sessionId, "dev-1", "operator@x", null,
                Set.of(RemoteSessionCapability.VIEW_ONLY));
    }

    private static RemoteBridgeSession opened(RemoteBridgeSessionStore store, String sessionId) {
        RemoteBridgeSessionStore.OpenResult result =
                store.open(request(sessionId), PEER, "Op Erator", PROMPT_EXPIRY, NOW);
        assertTrue(result instanceof RemoteBridgeSessionStore.Opened, String.valueOf(result));
        return ((RemoteBridgeSessionStore.Opened) result).session();
    }

    // --- PeerTrustLedger -----------------------------------------------------

    @Test
    void failClosedParserYieldsAllFalseTrust() {
        PeerTrustLedger ledger = ledger(PeerEvidenceParser.FAIL_CLOSED, true,
                AttestationVerifier.AttestationDecision.VERIFIED);
        PeerTrustLedger.PeerTrust trust = ledger.record(PEER, hello(), NOW);
        assertFalse(trust.certTrusted());
        assertFalse(trust.attestationVerified());
        assertFalse(trust.deviceTrusted());
        assertEquals(Optional.of("dev-1"), trust.certBoundDeviceId());
    }

    @Test
    void verifierOutcomesAreRecordedPerDimension() {
        PeerTrustLedger ledger = ledger(presentingParser(), true,
                AttestationVerifier.AttestationDecision.MISSING);
        PeerTrustLedger.PeerTrust trust = ledger.record(PEER, hello(), NOW);
        assertTrue(trust.certTrusted());
        assertFalse(trust.attestationVerified()); // verifier said UNVERIFIED
        assertFalse(trust.deviceTrusted());       // no device key presented
    }

    @Test
    void aThrowingVerifierRecordsFalseNeverPropagates() {
        CertTrustEvaluator exploding = (cert, now) -> {
            throw new IllegalStateException("CRL fetch died");
        };
        PeerTrustLedger ledger = new PeerTrustLedger(exploding,
                attestationResult(AttestationVerifier.AttestationDecision.VERIFIED),
                untrustingDeviceVerifier(), presentingParser(), 30_000);
        PeerTrustLedger.PeerTrust trust = ledger.record(PEER, hello(), NOW);
        assertFalse(trust.certTrusted());
        assertTrue(trust.attestationVerified());
    }

    @Test
    void freshnessTtlExpiresTheLedgerEntry() {
        PeerTrustLedger ledger = ledger(presentingParser(), true,
                AttestationVerifier.AttestationDecision.VERIFIED);
        ledger.record(PEER, hello(), NOW);
        assertTrue(ledger.fresh("peer-1", NOW + 29_999).isPresent());
        assertTrue(ledger.fresh("peer-1", NOW + 30_001).isEmpty()); // stale = no verification
        assertTrue(ledger.fresh("peer-1", NOW - 1).isEmpty());      // clock went backwards = no trust
        assertTrue(ledger.fresh("unknown", NOW).isEmpty());
    }

    // --- RemoteBridgeSessionStore -------------------------------------------

    @Test
    void openWalksTheMachineToConsentPending() {
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        RemoteBridgeSession session = opened(store, "sess-1");
        assertEquals(State.CONSENT_PENDING, session.state());
        assertEquals("peer-1", session.transportPeerKey());
        assertEquals(1, store.liveCount());
    }

    @Test
    void openRefusesInvalidDuplicatePastExpiryAndSecondLiveSessionPerPeer() {
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        assertTrue(store.open(new RemoteBridgeMessages.SessionRequest("s", "d", "o", null, Set.of()),
                PEER, "x", PROMPT_EXPIRY, NOW) instanceof RemoteBridgeSessionStore.Refused);
        assertTrue(store.open(request("sess-x"), PEER, "x", NOW - 1, NOW)
                instanceof RemoteBridgeSessionStore.Refused); // expiry not in the future
        opened(store, "sess-1");
        assertTrue(store.open(request("sess-1"), OTHER_PEER, "x", PROMPT_EXPIRY, NOW)
                instanceof RemoteBridgeSessionStore.Refused); // duplicate id
        RemoteBridgeSessionStore.OpenResult second = store.open(request("sess-2"), PEER, "x",
                PROMPT_EXPIRY, NOW);
        assertTrue(second instanceof RemoteBridgeSessionStore.Refused);
        assertEquals("peer-already-has-live-session",
                ((RemoteBridgeSessionStore.Refused) second).reason());
    }

    @Test
    void aTerminalSessionFreesThePeerSlot() {
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        RemoteBridgeSession session = opened(store, "sess-1");
        assertTrue(session.transition(Event.KILL).accepted());
        store.evictIfTerminal("sess-1");
        assertEquals(0, store.liveCount());
        assertTrue(store.open(request("sess-2"), PEER, "x", PROMPT_EXPIRY, NOW)
                instanceof RemoteBridgeSessionStore.Opened);
    }

    // --- consent flow ---------------------------------------------------------

    private BrokerControlPlane plane(RemoteBridgeSessionStore store, RecordingSinkStub sink) {
        return new BrokerControlPlane(
                ledger(PeerEvidenceParser.FAIL_CLOSED, false, AttestationVerifier.AttestationDecision.MISSING),
                store, sink, () -> NOW + 1000);
    }

    @Test
    void grantedConsentIsClampedToTheBrokerPromptWindow() {
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        RemoteBridgeSession session = opened(store, "sess-1");
        RecordingSinkStub sink = new RecordingSinkStub();
        BrokerControlPlane plane = plane(store, sink);

        // the agent claims a YEAR of consent — the broker clamps to its own prompt window
        plane.onConsentResult(PEER, new RemoteBridgeMessages.ConsentResult("sess-1", true, "Console",
                NOW + 1000, NOW + 365L * 24 * 3600 * 1000));
        assertEquals(State.CONSENT_GRANTED, session.state());
        assertTrue(session.lease().granted());
        assertEquals(PROMPT_EXPIRY, session.lease().expiryEpochMillis());
        assertTrue(sink.has("CONSENT_GRANTED"));
    }

    @Test
    void anAgentMayShortenItsOwnConsentWindow() {
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        RemoteBridgeSession session = opened(store, "sess-1");
        BrokerControlPlane plane = plane(store, new RecordingSinkStub());
        plane.onConsentResult(PEER, new RemoteBridgeMessages.ConsentResult("sess-1", true, "Console",
                NOW + 1000, NOW + 5_000));
        assertEquals(NOW + 5_000, session.lease().expiryEpochMillis());
    }

    @Test
    void consentRefusalsAreFailClosed() {
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        RemoteBridgeSession session = opened(store, "sess-1");
        RecordingSinkStub sink = new RecordingSinkStub();
        BrokerControlPlane plane = plane(store, sink);

        plane.onConsentResult(PEER, new RemoteBridgeMessages.ConsentResult("ghost", true, "Console",
                NOW, PROMPT_EXPIRY));
        assertTrue(sink.has("CONSENT_REFUSED:unknown-session"));

        plane.onConsentResult(OTHER_PEER, new RemoteBridgeMessages.ConsentResult("sess-1", true, "Console",
                NOW, PROMPT_EXPIRY));
        assertTrue(sink.has("CONSENT_REFUSED:wrong-peer"));
        assertEquals(State.CONSENT_PENDING, session.state()); // untouched by the foreign peer

        plane.onConsentResult(PEER, new RemoteBridgeMessages.ConsentResult("sess-1", true, "Console",
                NOW, PROMPT_EXPIRY));
        assertEquals(State.CONSENT_GRANTED, session.state());
        // a DUPLICATE grant replay is refused by the machine (no longer CONSENT_PENDING)
        plane.onConsentResult(PEER, new RemoteBridgeMessages.ConsentResult("sess-1", true, "Console",
                NOW, PROMPT_EXPIRY));
        assertTrue(sink.has("CONSENT_REFUSED:not-pending"));
    }

    @Test
    void aLateConsentIsRefused() {
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        RemoteBridgeSession session = opened(store, "sess-1");
        RecordingSinkStub sink = new RecordingSinkStub();
        BrokerControlPlane late = new BrokerControlPlane(
                ledger(PeerEvidenceParser.FAIL_CLOSED, false, AttestationVerifier.AttestationDecision.MISSING),
                store, sink, () -> PROMPT_EXPIRY + 1); // the prompt window has passed
        late.onConsentResult(PEER, new RemoteBridgeMessages.ConsentResult("sess-1", true, "Console",
                NOW, PROMPT_EXPIRY));
        assertTrue(sink.has("CONSENT_REFUSED:late"));
        assertEquals(State.CONSENT_PENDING, session.state());
        assertFalse(session.lease().granted());
    }

    @Test
    void deniedConsentTerminatesTheSessionAndFreesThePeer() {
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        RemoteBridgeSession session = opened(store, "sess-1");
        RecordingSinkStub sink = new RecordingSinkStub();
        plane(store, sink).onConsentResult(PEER,
                new RemoteBridgeMessages.ConsentResult("sess-1", false, "Console", NOW, PROMPT_EXPIRY));
        assertTrue(session.isTerminal());
        assertEquals(0, store.liveCount());
        assertTrue(sink.has("CONSENT_DENIED"));
    }

    @Test
    void aZeroOrPastExpiryGrantIsRefusedNeverEscalated() {
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        RemoteBridgeSession session = opened(store, "sess-1");
        RecordingSinkStub sink = new RecordingSinkStub();
        BrokerControlPlane plane = plane(store, sink);

        // proto3 default 0 — a malformed grant must NOT become a full-window lease
        plane.onConsentResult(PEER, new RemoteBridgeMessages.ConsentResult("sess-1", true, "Console",
                NOW, 0));
        assertEquals(State.CONSENT_PENDING, session.state());
        assertFalse(session.lease().granted());
        assertTrue(sink.has("CONSENT_REFUSED:invalid-expiry"));

        // an expiry exactly at/below 'now' is equally no grant
        plane.onConsentResult(PEER, new RemoteBridgeMessages.ConsentResult("sess-1", true, "Console",
                NOW, NOW + 1000)); // plane clock is NOW+1000 → not in the future
        assertEquals(State.CONSENT_PENDING, session.state());
        assertFalse(session.lease().granted());
    }

    // --- inbound audit ---------------------------------------------------------

    @Test
    void localAbortAbortsTheLeaseAndKillsTheSession() {
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        RemoteBridgeSession session = opened(store, "sess-1");
        RecordingSinkStub sink = new RecordingSinkStub();
        BrokerControlPlane plane = plane(store, sink);
        plane.onConsentResult(PEER, new RemoteBridgeMessages.ConsentResult("sess-1", true, "Console",
                NOW, PROMPT_EXPIRY));

        plane.onAuditEvent(PEER, new RemoteBridgeMessages.AuditEvent("sess-1",
                BrokerControlPlane.EVENT_LOCAL_ABORT, "", NOW + 2000));
        assertEquals(State.KILLED, session.state());
        assertTrue(session.lease().locallyAborted());
        assertEquals(0, store.liveCount());
        assertTrue(sink.has("KILLED:local-abort"));
    }

    @Test
    void indicatorLossAbortsTheLeaseAndKillsLikeALocalAbort() {
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        RemoteBridgeSession session = opened(store, "sess-1");
        RecordingSinkStub sink = new RecordingSinkStub();
        BrokerControlPlane plane = plane(store, sink);
        plane.onConsentResult(PEER, new RemoteBridgeMessages.ConsentResult("sess-1", true, "Console",
                NOW, PROMPT_EXPIRY));

        // the user can no longer SEE that support is active — the lease contract treats this as an abort
        plane.onAuditEvent(PEER, new RemoteBridgeMessages.AuditEvent("sess-1",
                BrokerControlPlane.EVENT_INDICATOR_LOST, "", NOW + 2000));
        assertEquals(State.KILLED, session.state());
        assertTrue(session.lease().locallyAborted());
        assertEquals(0, store.liveCount());
        assertTrue(sink.has("KILLED:indicator-lost"));
    }

    @Test
    void aThrowingParserStillRecordsFailClosedAllFalse() {
        PeerEvidenceParser exploding = (peer, hello) -> {
            throw new IllegalStateException("malformed evidence bytes");
        };
        PeerTrustLedger ledger = new PeerTrustLedger(certResult(true),
                attestationResult(AttestationVerifier.AttestationDecision.VERIFIED),
                untrustingDeviceVerifier(), exploding, 30_000);
        PeerTrustLedger.PeerTrust trust = ledger.record(PEER, hello(), NOW);
        assertFalse(trust.certTrusted());
        assertFalse(trust.attestationVerified());
        assertFalse(trust.deviceTrusted());
    }

    @Test
    void aForeignPeerCannotLocalAbortAnotherDevicesSession() {
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        RemoteBridgeSession session = opened(store, "sess-1");
        plane(store, new RecordingSinkStub()).onAuditEvent(OTHER_PEER,
                new RemoteBridgeMessages.AuditEvent("sess-1", BrokerControlPlane.EVENT_LOCAL_ABORT, "", NOW));
        assertFalse(session.isTerminal());
    }

    @Test
    void nonAllowlistedInboundAuditTypesAreRefused() {
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        opened(store, "sess-1");
        RecordingSinkStub sink = new RecordingSinkStub();
        plane(store, sink).onAuditEvent(PEER, new RemoteBridgeMessages.AuditEvent("sess-1",
                "ALLOW_DECISION:op-9", "", NOW)); // broker-shaped authority from an agent — refused
        assertTrue(sink.has("AGENT_AUDIT_REFUSED"));
        assertFalse(sink.has("AGENT:ALLOW_DECISION"));
    }

    @Test
    void aDownRecorderNeverBlocksTheSafeOutcome() {
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        RemoteBridgeSession session = opened(store, "sess-1");
        RecordingSinkStub sink = new RecordingSinkStub();
        sink.failAll = true;
        BrokerControlPlane plane = plane(store, sink);
        plane.onConsentResult(PEER, new RemoteBridgeMessages.ConsentResult("sess-1", false, "Console",
                NOW, PROMPT_EXPIRY));
        assertTrue(session.isTerminal()); // the denial still lands — fail-safe beats audit
    }

    // --- session primitives ------------------------------------------------------

    @Test
    void seqIsMonotonicAndStateOnlyMovesThroughTheMachine() {
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        RemoteBridgeSession session = opened(store, "sess-1");
        assertEquals(0, session.nextSeq());
        assertEquals(1, session.nextSeq());
        assertFalse(session.transition(Event.ACTIVATE).accepted()); // CONSENT_PENDING → ACTIVATE illegal
        assertEquals(State.CONSENT_PENDING, session.state());
        assertTrue(session.transition(Event.KILL).accepted());      // safety override always fires
        assertEquals(State.KILLED, session.state());
    }
}

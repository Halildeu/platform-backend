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
import com.example.endpointadmin.remoteaccess.bridge.server.viewonly.ViewOnlySessionLifecycle;
import com.example.endpointadmin.remoteaccess.bridge.server.viewonly.ViewOnlyStreamAuthorizationRegistry;
import com.example.endpointadmin.remoteaccess.bridge.server.viewonly.ViewOnlyViewerRegistry;
import com.example.endpointadmin.remoteaccess.bridge.server.viewonly.ViewOnlyViewerSubscription;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

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
    // a canonical operator-tenant UUID — the store enforces the canonical form (slice-4c-2b-2b)
    private static final String TENANT = "11111111-1111-1111-1111-111111111111";

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

        boolean hasExact(String type) {
            return events.stream().anyMatch(r -> r.eventType().equals(type));
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
        return request(sessionId, Set.of(RemoteSessionCapability.VIEW_ONLY));
    }

    private static RemoteBridgeMessages.SessionRequest request(
            String sessionId, Set<RemoteSessionCapability> capabilities) {
        return new RemoteBridgeMessages.SessionRequest(sessionId, "dev-1", "operator@x", null,
                capabilities);
    }

    private static RemoteBridgeSession opened(RemoteBridgeSessionStore store, String sessionId) {
        return opened(store, sessionId, Set.of(RemoteSessionCapability.VIEW_ONLY));
    }

    private static RemoteBridgeSession opened(
            RemoteBridgeSessionStore store, String sessionId, Set<RemoteSessionCapability> capabilities) {
        RemoteBridgeSessionStore.OpenResult result =
                store.open(request(sessionId, capabilities), PEER, TENANT, "Op Erator", PROMPT_EXPIRY, NOW);
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

    @Test
    void heartbeatDoesNotCreateTrustBeforeAgentHello() {
        AtomicLong now = new AtomicLong(NOW);
        PeerTrustLedger ledger = ledger(presentingParser(), true,
                AttestationVerifier.AttestationDecision.VERIFIED);
        BrokerControlPlane plane = new BrokerControlPlane(ledger, new RemoteBridgeSessionStore(),
                new RecordingSinkStub(), now::get);

        plane.onHeartbeat(PEER);

        assertTrue(ledger.fresh("peer-1", NOW).isEmpty());
    }

    @Test
    void heartbeatRefreshesTheLastHelloEvidenceForLongLivedControlStreams() {
        AtomicLong now = new AtomicLong(NOW);
        PeerTrustLedger ledger = ledger(presentingParser(), true,
                AttestationVerifier.AttestationDecision.VERIFIED);
        BrokerControlPlane plane = new BrokerControlPlane(ledger, new RemoteBridgeSessionStore(),
                new RecordingSinkStub(), now::get);

        plane.onAgentHello(PEER, hello());
        assertTrue(ledger.fresh("peer-1", NOW + 30_001).isEmpty()); // stale without an inbound heartbeat

        now.set(NOW + 29_000);
        plane.onHeartbeat(PEER);

        assertTrue(ledger.fresh("peer-1", NOW + 59_000).isPresent());
        assertTrue(ledger.fresh("peer-1", NOW + 59_001).isEmpty());
    }

    @Test
    void acceptedConsentRefreshesLastHelloEvidenceForAgentsWithoutHeartbeatFrames() {
        AtomicLong now = new AtomicLong(NOW);
        PeerTrustLedger ledger = ledger(presentingParser(), true,
                AttestationVerifier.AttestationDecision.VERIFIED);
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        RemoteBridgeSession session = opened(store, "sess-1");
        RecordingSinkStub sink = new RecordingSinkStub();
        BrokerControlPlane plane = new BrokerControlPlane(ledger, store, sink, now::get);

        plane.onAgentHello(PEER, hello());
        assertTrue(ledger.fresh("peer-1", NOW + 30_001).isEmpty());

        now.set(NOW + 31_000);
        plane.onConsentResult(PEER, new RemoteBridgeMessages.ConsentResult("sess-1", true, "Console",
                NOW + 31_000, PROMPT_EXPIRY));

        assertEquals(State.ACTIVE, session.state());
        assertTrue(ledger.fresh("peer-1", NOW + 60_999).isPresent());
        assertTrue(ledger.fresh("peer-1", NOW + 61_001).isEmpty());
        assertTrue(sink.has("CONSENT_TRUST_REFRESHED:cert=true,attestation=true,device=false"));
        // Faz 22.6 #1580: consent-time re-verification also emits a session-scoped HELLO_VERIFIED so a
        // session-filtered audit stream observes the hello verification (onAgentHello only records the
        // peer-level HELLO_VERIFIED to the "ledger" sink, before any session exists).
        assertTrue(sink.events.stream().anyMatch(r -> r.sessionId().equals("sess-1")
                && r.eventType().startsWith("HELLO_VERIFIED:source=consent-refresh,cert=true,attestation=true,device=false")));
        // The original peer-level hello stays on the "ledger" sink for peer diagnostics — not relabeled as the
        // session-scoped consent-refresh record.
        assertTrue(sink.events.stream().anyMatch(r -> r.sessionId().equals("ledger")
                && r.eventType().startsWith("HELLO_VERIFIED:")
                && !r.eventType().contains("source=consent-refresh")));
    }

    @Test
    void consentTimeHelloReverificationIsSessionScopedAndUniquePerAcceptedConsent() {
        AtomicLong now = new AtomicLong(NOW);
        PeerTrustLedger ledger = ledger(presentingParser(), true,
                AttestationVerifier.AttestationDecision.VERIFIED);
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        opened(store, "sess-1");
        RecordingSinkStub sink = new RecordingSinkStub();
        BrokerControlPlane plane = new BrokerControlPlane(ledger, store, sink, now::get);

        plane.onAgentHello(PEER, hello());
        now.set(NOW + 31_000);
        plane.onConsentResult(PEER, new RemoteBridgeMessages.ConsentResult("sess-1", true, "Console",
                NOW + 31_000, PROMPT_EXPIRY));
        // A replayed/duplicate consent is refused by the state machine (CONSENT_GRANTED only from
        // CONSENT_PENDING), so the session-scoped re-verification does not fire a second time.
        plane.onConsentResult(PEER, new RemoteBridgeMessages.ConsentResult("sess-1", true, "Console",
                NOW + 31_000, PROMPT_EXPIRY));

        long sessionScoped = sink.events.stream()
                .filter(r -> r.sessionId().equals("sess-1"))
                .filter(r -> r.eventType().startsWith("HELLO_VERIFIED:source=consent-refresh"))
                .count();
        assertEquals(1L, sessionScoped, "exactly one session-scoped HELLO_VERIFIED per accepted consent");
        assertTrue(sink.has("CONSENT_REFUSED:not-pending"));
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
                PEER, TENANT, "x", PROMPT_EXPIRY, NOW) instanceof RemoteBridgeSessionStore.Refused);
        assertTrue(store.open(request("sess-x"), PEER, TENANT, "x", NOW - 1, NOW)
                instanceof RemoteBridgeSessionStore.Refused); // expiry not in the future
        opened(store, "sess-1");
        assertTrue(store.open(request("sess-1"), OTHER_PEER, TENANT, "x", PROMPT_EXPIRY, NOW)
                instanceof RemoteBridgeSessionStore.Refused); // duplicate id
        RemoteBridgeSessionStore.OpenResult second = store.open(request("sess-2"), PEER, TENANT, "x",
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
        assertTrue(store.open(request("sess-2"), PEER, TENANT, "x", PROMPT_EXPIRY, NOW)
                instanceof RemoteBridgeSessionStore.Opened);
    }

    // --- device-key session attestation consumer (Faz 22.6 #548 step-5a) ------

    @Test
    void aLateDeviceKeyResponseForAGoneSessionIsNotStoredForTheNewSessionOnTheSamePeer() {
        // Codex F1: a challenge issued for session A must NOT store evidence for a NEW session B that the same
        // reconnected peer later opens — the challenge is session-bound and the consumer resolves liveByPeer
        // THEN consumes with that session id, so A's late response is dropped (never bound to B).
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        DeviceKeyChallengeStore challengeStore = new DeviceKeyChallengeStore();
        TpmDeviceKeySessionEvidenceStore evidenceStore = new TpmDeviceKeySessionEvidenceStore();
        RecordingSinkStub sink = new RecordingSinkStub();
        BrokerControlPlane plane = new BrokerControlPlane(
                ledger(PeerEvidenceParser.FAIL_CLOSED, false, AttestationVerifier.AttestationDecision.MISSING),
                store, sink, () -> NOW + 1000, new RemoteBridgeAgentErrorLedger(0), challengeStore, evidenceStore);

        RemoteBridgeSession a = opened(store, "sess-A");
        RemoteBridgeMessages.DeviceKeyChallenge challenge =
                challengeStore.issue("sess-A", PEER.transportPeerKey(), 60_000L, NOW);
        assertTrue(a.transition(Event.KILL).accepted());
        store.evictIfTerminal("sess-A");
        opened(store, "sess-B"); // B is now the peer's live session

        plane.onDeviceKeyAttestationResponse(PEER, shapedDeviceKeyResponse(challenge.challengeId()));

        assertTrue(evidenceStore.consumeFresh("sess-B", PEER.transportPeerKey(), NOW + 1000).isEmpty(),
                "a challenge bound to the gone session A must not store evidence for the new session B");
        assertTrue(evidenceStore.consumeFresh("sess-A", PEER.transportPeerKey(), NOW + 1000).isEmpty());
        assertTrue(sink.has("DEVICE_KEY_RESPONSE_DROPPED:no-live-challenge"));
    }

    @Test
    void aDeviceKeyResponseForThePeersLiveSessionIsStored() {
        // the positive correlation: a response answering THIS live session's challenge IS stored (shape-only;
        // the DEVICE_KEY_ATTESTATION_REAL verifier still re-derives every fact at PERMIT time).
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        DeviceKeyChallengeStore challengeStore = new DeviceKeyChallengeStore();
        TpmDeviceKeySessionEvidenceStore evidenceStore = new TpmDeviceKeySessionEvidenceStore();
        RecordingSinkStub sink = new RecordingSinkStub();
        BrokerControlPlane plane = new BrokerControlPlane(
                ledger(PeerEvidenceParser.FAIL_CLOSED, false, AttestationVerifier.AttestationDecision.MISSING),
                store, sink, () -> NOW + 1000, new RemoteBridgeAgentErrorLedger(0), challengeStore, evidenceStore);

        RemoteBridgeSession session = opened(store, "sess-1");
        RemoteBridgeMessages.DeviceKeyChallenge challenge =
                challengeStore.issue("sess-1", PEER.transportPeerKey(), 60_000L, NOW);
        session.bindDeviceKeyChallenge(challenge.challengeId()); // openSession does this live (incarnation guard)
        plane.onDeviceKeyAttestationResponse(PEER, shapedDeviceKeyResponse(challenge.challengeId()));

        assertTrue(evidenceStore.consumeFresh("sess-1", PEER.transportPeerKey(), NOW + 1000).isPresent(),
                "the peer's own live-session response is stored");
        assertTrue(sink.hasExact("DEVICE_KEY_EVIDENCE_STORED:challenge_hash="
                + shortAuditHash(challenge.challengeId())));
    }

    /** A well-SHAPED (mappable) device-key response — the consumer maps shape only; crypto is the verifier's job. */
    private static RemoteBridgeMessages.DeviceKeyAttestationResponse shapedDeviceKeyResponse(String challengeId) {
        String b = "AQ=="; // base64 of one byte → satisfies the mapper's required-non-empty fields
        return new RemoteBridgeMessages.DeviceKeyAttestationResponse(challengeId,
                "faz22.6.device-key-session.v1", b, b, b, "", "", List.of(), b, b, b, b, b, b, NOW);
    }

    private static String shortAuditHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
        assertEquals(State.ACTIVE, session.state()); // D10.1: a granted consent activates the session
        assertTrue(session.lease().granted());
        assertEquals(PROMPT_EXPIRY, session.lease().expiryEpochMillis());
        assertTrue(sink.has("CONSENT_GRANTED"));
    }

    @Test
    void aValidGrantedConsentActivatesTheSessionForTheTransport() {
        // D10.1 (#634): a granted consent moves the session to ACTIVE (transport readiness) so the operator
        // transport can drive operations — the operation PERMIT still goes through the full policy at that time
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        RemoteBridgeSession session = opened(store, "sess-1");
        RecordingSinkStub sink = new RecordingSinkStub();
        BrokerControlPlane plane = plane(store, sink);
        plane.onConsentResult(PEER, new RemoteBridgeMessages.ConsentResult("sess-1", true, "Console",
                NOW + 1000, PROMPT_EXPIRY));
        assertEquals(State.ACTIVE, session.state());
        assertTrue(sink.has("ACTIVE:lease-until=" + session.lease().expiryEpochMillis()));
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
        assertEquals(State.ACTIVE, session.state()); // D10.1: a granted consent activates the session
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
    void controlStreamLossKillsSessionRevokesTrustAndTerminatesViewOnlyExactlyOnce() {
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        RemoteBridgeSession session = opened(store, "sess-1");
        RecordingSinkStub sink = new RecordingSinkStub();
        PeerTrustLedger trust = ledger(presentingParser(), true,
                AttestationVerifier.AttestationDecision.VERIFIED);
        DeviceKeyChallengeStore challengeStore = new DeviceKeyChallengeStore();
        TpmDeviceKeySessionEvidenceStore evidenceStore = new TpmDeviceKeySessionEvidenceStore();
        BrokerControlPlane plane = new BrokerControlPlane(trust, store, sink, () -> NOW + 1000,
                new RemoteBridgeAgentErrorLedger(0), challengeStore, evidenceStore);
        plane.onAgentHello(PEER, hello());
        assertTrue(trust.fresh(PEER.transportPeerKey(), NOW + 1000).isPresent());

        RemoteBridgeMessages.DeviceKeyChallenge consumed =
                challengeStore.issue("sess-1", PEER.transportPeerKey(), 60_000L, NOW);
        session.bindDeviceKeyChallenge(consumed.challengeId());
        plane.onDeviceKeyAttestationResponse(PEER, shapedDeviceKeyResponse(consumed.challengeId()));
        assertTrue(evidenceStore.consumeFresh("sess-1", PEER.transportPeerKey(), NOW + 1000).isPresent());
        challengeStore.issue("sess-1", PEER.transportPeerKey(), 60_000L, NOW + 500);
        assertEquals(1, challengeStore.pendingCount());

        ViewOnlyStreamAuthorizationRegistry authz = new ViewOnlyStreamAuthorizationRegistry();
        ViewOnlyViewerRegistry viewers = new ViewOnlyViewerRegistry(1);
        plane.configureViewOnlyLifecycle(new ViewOnlySessionLifecycle(authz, viewers));
        plane.onConsentResult(PEER, new RemoteBridgeMessages.ConsentResult("sess-1", true, "Console",
                NOW, PROMPT_EXPIRY));

        Object incarnation = new Object();
        authz.beginSession("sess-1", incarnation);
        authz.authorize(incarnation, new ViewOnlyStreamAuthorizationRegistry.Authorization(
                "sess-1", "op-1", PEER.transportPeerKey(), "operator@x", "dev-1", PROMPT_EXPIRY));
        ViewOnlyViewerSubscription viewer = viewers.subscribe(
                "sess-1", "op-1", TENANT, "operator@x", null).orElseThrow();

        plane.onControlStreamClosed(PEER);
        plane.onControlStreamClosed(PEER); // a late duplicate transport callback is harmless

        assertEquals(State.KILLED, session.state());
        assertEquals(0, store.liveCount());
        assertTrue(trust.fresh(PEER.transportPeerKey(), NOW + 1000).isEmpty());
        assertFalse(authz.isAuthorized("sess-1", "op-1", PEER.transportPeerKey(), NOW + 1000));
        assertTrue(viewer.isClosed());
        assertEquals(0, viewers.viewerCount("sess-1"));
        assertEquals(0, challengeStore.pendingCount());
        assertTrue(evidenceStore.consumeFresh("sess-1", PEER.transportPeerKey(), NOW + 1000).isEmpty());
        assertEquals(1, sink.events.stream()
                .filter(event -> event.eventType().equals("KILLED:control-stream-lost"))
                .count());
    }

    // --- #1580: agent-driven terminals must terminate the VIEW_ONLY surface (Codex 019f0e78) ----------

    @Test
    void localAbortTerminatesViewOnlyFanoutAuthorizationAndViewers() {
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        opened(store, "sess-1");
        BrokerControlPlane plane = plane(store, new RecordingSinkStub());

        ViewOnlyStreamAuthorizationRegistry authz = new ViewOnlyStreamAuthorizationRegistry();
        ViewOnlyViewerRegistry viewers = new ViewOnlyViewerRegistry(1);
        plane.configureViewOnlyLifecycle(new ViewOnlySessionLifecycle(authz, viewers));
        // drive the session to ACTIVE
        plane.onConsentResult(PEER, new RemoteBridgeMessages.ConsentResult("sess-1", true, "Console",
                NOW, PROMPT_EXPIRY));

        // an authorized VIEW_ONLY screen stream + a subscribed operator viewer, bound to this peer
        Object incarnation = new Object();
        authz.beginSession("sess-1", incarnation);
        authz.authorize(incarnation, new ViewOnlyStreamAuthorizationRegistry.Authorization(
                "sess-1", "op-1", "peer-1", "operator@x", "dev-1", PROMPT_EXPIRY));
        ViewOnlyViewerSubscription viewer = viewers.subscribe(
                "sess-1", "op-1", "tenant-1", "operator@x", null).orElseThrow();
        assertTrue(authz.isAuthorized("sess-1", "op-1", "peer-1", NOW));

        // the endpoint user aborts → the broker's agent-driven terminal MUST terminate the view-only surface
        plane.onAuditEvent(PEER, new RemoteBridgeMessages.AuditEvent("sess-1",
                BrokerControlPlane.EVENT_LOCAL_ABORT, "", NOW + 2000));

        assertFalse(authz.isAuthorized("sess-1", "op-1", "peer-1", NOW + 2000)); // grant revoked — no stale fanout
        assertTrue(viewer.isClosed());                                            // viewer detached
        assertEquals(0, viewers.viewerCount("sess-1"));
    }

    @Test
    void consentDeniedTerminatesViewOnlyFanoutAuthorizationAndViewers() {
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        opened(store, "sess-1");
        BrokerControlPlane plane = plane(store, new RecordingSinkStub());

        ViewOnlyStreamAuthorizationRegistry authz = new ViewOnlyStreamAuthorizationRegistry();
        ViewOnlyViewerRegistry viewers = new ViewOnlyViewerRegistry(1);
        plane.configureViewOnlyLifecycle(new ViewOnlySessionLifecycle(authz, viewers));

        // (a defence-in-depth grant could exist before consent resolves) — a denial must still clean it up
        Object incarnation = new Object();
        authz.beginSession("sess-1", incarnation);
        authz.authorize(incarnation, new ViewOnlyStreamAuthorizationRegistry.Authorization(
                "sess-1", "op-1", "peer-1", "operator@x", "dev-1", PROMPT_EXPIRY));
        ViewOnlyViewerSubscription viewer = viewers.subscribe(
                "sess-1", "op-1", "tenant-1", "operator@x", null).orElseThrow();

        plane.onConsentResult(PEER, new RemoteBridgeMessages.ConsentResult("sess-1", false, "Console",
                NOW, PROMPT_EXPIRY));

        assertFalse(authz.isAuthorized("sess-1", "op-1", "peer-1", NOW));
        assertTrue(viewer.isClosed());
        assertEquals(0, viewers.viewerCount("sess-1"));
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
    void agentErrorFramesAreRecordedAsMetadataOnly() {
        RecordingSinkStub sink = new RecordingSinkStub();
        BrokerControlPlane plane = plane(new RemoteBridgeSessionStore(), sink);

        plane.onAgentErrorFrame(PEER, new RemoteBridgeMessages.AgentErrorFrame("sess-1",
                "operation-dispatch-failed", false, "contains local details but is not persisted"));

        assertTrue(sink.has("AGENT_ERROR:operation-dispatch-failed:retryable=false"));
        assertFalse(sink.events.stream().anyMatch(e -> e.eventType().contains("local details")));
    }

    @Test
    void nonRetryableScreenViewPermitExpiryTerminatesTheBoundActiveViewOnlySession() {
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        RemoteBridgeSession session = opened(store, "sess-1");
        RecordingSinkStub sink = new RecordingSinkStub();
        RemoteBridgeAgentErrorLedger errorLedger = new RemoteBridgeAgentErrorLedger(16);
        BrokerControlPlane plane = new BrokerControlPlane(
                ledger(PeerEvidenceParser.FAIL_CLOSED, false, AttestationVerifier.AttestationDecision.MISSING),
                store, sink, () -> NOW + 1000, errorLedger);
        ViewOnlyStreamAuthorizationRegistry authz = new ViewOnlyStreamAuthorizationRegistry();
        ViewOnlyViewerRegistry viewers = new ViewOnlyViewerRegistry(1);
        plane.configureViewOnlyLifecycle(new ViewOnlySessionLifecycle(authz, viewers));
        plane.onConsentResult(PEER, new RemoteBridgeMessages.ConsentResult(
                "sess-1", true, "Console", NOW, PROMPT_EXPIRY));
        Object incarnation = new Object();
        authz.beginSession("sess-1", incarnation);
        authz.authorize(incarnation, new ViewOnlyStreamAuthorizationRegistry.Authorization(
                "sess-1", "op-1", PEER.transportPeerKey(), "operator@x", "dev-1", PROMPT_EXPIRY));
        ViewOnlyViewerSubscription viewer = viewers.subscribe(
                "sess-1", "op-1", TENANT, "operator@x", null).orElseThrow();

        plane.onAgentErrorFrame(PEER, new RemoteBridgeMessages.AgentErrorFrame(
                "sess-1", BrokerControlPlane.ERROR_SCREEN_VIEW_PERMIT_EXPIRED, false, "redacted"));

        assertEquals(State.KILLED, session.state());
        assertEquals(0, store.liveCount());
        assertTrue(viewer.isClosed());
        assertFalse(authz.isAuthorized("sess-1", "op-1", PEER.transportPeerKey(), NOW + 1000));
        assertTrue(sink.hasExact("AGENT_ERROR:screen-view-permit-expired:retryable=false"));
        assertTrue(sink.hasExact("KILLED:screen-view-permit-expired"));
        assertTrue(errorLedger.findAfter("sess-1", PEER.transportPeerKey(),
                BrokerControlPlane.ERROR_SCREEN_VIEW_PERMIT_EXPIRED, NOW).isPresent());
    }

    @Test
    void screenViewPermitExpiryCannotTerminateOutsideItsExactBoundedContract() {
        record ErrorVariant(PeerIdentity peer, String code, boolean retryable, boolean active,
                            Set<RemoteSessionCapability> capabilities) {
        }
        List<ErrorVariant> variants = List.of(
                new ErrorVariant(OTHER_PEER, BrokerControlPlane.ERROR_SCREEN_VIEW_PERMIT_EXPIRED,
                        false, true, Set.of(RemoteSessionCapability.VIEW_ONLY)),
                new ErrorVariant(PEER, BrokerControlPlane.ERROR_SCREEN_VIEW_PERMIT_EXPIRED,
                        true, true, Set.of(RemoteSessionCapability.VIEW_ONLY)),
                new ErrorVariant(PEER, "operation-dispatch-failed",
                        false, true, Set.of(RemoteSessionCapability.VIEW_ONLY)),
                new ErrorVariant(PEER, BrokerControlPlane.ERROR_SCREEN_VIEW_PERMIT_EXPIRED,
                        false, false, Set.of(RemoteSessionCapability.VIEW_ONLY)),
                new ErrorVariant(PEER, BrokerControlPlane.ERROR_SCREEN_VIEW_PERMIT_EXPIRED,
                        false, true, Set.of(RemoteSessionCapability.CONSTRAINED_PTY)));

        for (ErrorVariant variant : variants) {
            RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
            // The protected resource belongs to PEER; OTHER_PEER is deliberately the unbound caller.
            RemoteBridgeSession session = opened(store, "sess-1", variant.capabilities());
            BrokerControlPlane plane = plane(store, new RecordingSinkStub());
            if (variant.active()) {
                plane.onConsentResult(PEER, new RemoteBridgeMessages.ConsentResult(
                        "sess-1", true, "Console", NOW, PROMPT_EXPIRY));
            }

            plane.onAgentErrorFrame(variant.peer(), new RemoteBridgeMessages.AgentErrorFrame(
                    "sess-1", variant.code(), variant.retryable(), "redacted"));

            assertFalse(session.isTerminal(), String.valueOf(variant));
            assertEquals(1, store.liveCount(), String.valueOf(variant));
        }

        for (String invalidSessionId : new String[] {null, "", " "}) {
            RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
            RemoteBridgeSession session = opened(store, "sess-1");
            BrokerControlPlane plane = plane(store, new RecordingSinkStub());
            plane.onConsentResult(PEER, new RemoteBridgeMessages.ConsentResult(
                    "sess-1", true, "Console", NOW, PROMPT_EXPIRY));

            plane.onAgentErrorFrame(PEER, new RemoteBridgeMessages.AgentErrorFrame(
                    invalidSessionId, BrokerControlPlane.ERROR_SCREEN_VIEW_PERMIT_EXPIRED,
                    false, "redacted"));

            assertFalse(session.isTerminal(), String.valueOf(invalidSessionId));
            assertEquals(1, store.liveCount(), String.valueOf(invalidSessionId));
        }
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
        assertEquals(1, session.nextSeq());
        assertEquals(2, session.nextSeq());
        assertFalse(session.transition(Event.ACTIVATE).accepted()); // CONSENT_PENDING → ACTIVATE illegal
        assertEquals(State.CONSENT_PENDING, session.state());
        assertTrue(session.transition(Event.KILL).accepted());      // safety override always fires
        assertEquals(State.KILLED, session.state());
    }
}

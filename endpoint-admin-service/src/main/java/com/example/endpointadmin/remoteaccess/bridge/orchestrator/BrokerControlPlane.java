package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeAuditSink;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeSessionStateMachine.Event;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages;
import com.example.endpointadmin.remoteaccess.bridge.server.ControlPlaneHandler;
import com.example.endpointadmin.remoteaccess.bridge.server.PeerIdentity;
import com.example.endpointadmin.remoteaccess.bridge.server.viewonly.ViewOnlySessionLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Faz 22.6 T-4a-i (Codex 019ebbfa P4) — the REAL inbound control plane: replaces the T-2b INERT seam for the
 * agent→broker direction. Fail-closed on every path; nothing here ever ISSUES authority (consent prompts,
 * permits, and the operator side are T-4a-ii) — this slice only ABSORBS agent events into broker state:
 *
 * <ul>
 *   <li><b>AgentHello</b> → verifier outcomes into the {@link PeerTrustLedger} (hello = verifier INPUT,
 *       never authority).</li>
 *   <li><b>ConsentResult</b> → the peer's own CONSENT_PENDING session only (wrong peer / unknown session /
 *       no pending prompt / late / duplicate all refused); the lease expiry is CLAMPED to the broker's
 *       prompt window; denial drives CONSENT_DENIED (terminal via the machine).</li>
 *   <li><b>AuditEvent</b> → eventType allowlist; {@code LOCAL_ABORT} is not mere telemetry — it aborts the
 *       lease and KILLs the session through the machine (the endpoint user's withdrawal always wins).</li>
 * </ul>
 */
public final class BrokerControlPlane implements ControlPlaneHandler {

    private static final Logger log = LoggerFactory.getLogger(BrokerControlPlane.class);

    /** The endpoint user's local consent withdrawal (ADR-0034 D2/D10-7 — always honored, kills the session). */
    public static final String EVENT_LOCAL_ABORT = "LOCAL_ABORT";

    /** The visible 'remote-support active' indicator could not be kept on screen (D10-6). */
    public static final String EVENT_INDICATOR_LOST = "AGENT_INDICATOR_LOST";

    /** Agent-originated audit metadata the broker accepts; anything else is refused (audited, dropped). */
    private static final Set<String> INBOUND_AUDIT_ALLOWLIST = Set.of(
            EVENT_LOCAL_ABORT,
            "AGENT_INDICATOR_SHOWN",   // the indicator came up (D10-6)
            EVENT_INDICATOR_LOST);

    private final PeerTrustLedger ledger;
    private final RemoteBridgeSessionStore store;
    private final RemoteBridgeAuditSink auditSink;
    private final LongSupplier clock;
    private final RemoteBridgeAgentErrorLedger agentErrorLedger;
    // Faz 22.6 #548 slice-1 step-5 — the canonical device-key challenge-response state. OPTIONAL: when either is
    // null the device-key path stays INERT (the prior T-2b accept-and-ignore default), so existing wiring/tests
    // are unaffected; the issuance trigger (step-5b) is what makes a challenge exist to be consumed at all.
    private final DeviceKeyChallengeStore deviceKeyChallengeStore;
    private final TpmDeviceKeySessionEvidenceStore deviceKeyEvidenceStore;
    private final Map<String, RemoteBridgeMessages.AgentHello> lastHelloByPeer = new ConcurrentHashMap<>();
    // Faz 22.6 #1580 (Codex 019f0e78) — terminate the VIEW_ONLY surface (revoke fanout authorization + close
    // viewers) on AGENT-driven terminals (consent-denied / local-abort / active-indicator-lost), so a stale
    // authorization can never keep fanning screen frames out after the endpoint user pulls consent. Optional
    // (null = no #1580 view-only plane); set post-construction by the server config.
    private volatile ViewOnlySessionLifecycle viewOnlyLifecycle;

    public BrokerControlPlane(PeerTrustLedger ledger,
                              RemoteBridgeSessionStore store,
                              RemoteBridgeAuditSink auditSink,
                              LongSupplier clock) {
        this(ledger, store, auditSink, clock, new RemoteBridgeAgentErrorLedger(0));
    }

    public BrokerControlPlane(PeerTrustLedger ledger,
                              RemoteBridgeSessionStore store,
                              RemoteBridgeAuditSink auditSink,
                              LongSupplier clock,
                              RemoteBridgeAgentErrorLedger agentErrorLedger) {
        this(ledger, store, auditSink, clock, agentErrorLedger, null, null);
    }

    /**
     * Faz 22.6 #548 slice-1 step-5 — the device-key-aware control plane. {@code deviceKeyChallengeStore} +
     * {@code deviceKeyEvidenceStore} wire the canonical TPM-native challenge-response consumer; either null
     * leaves {@link #onDeviceKeyAttestationResponse} inert (fail-closed: no evidence is ever stored, so the
     * {@code DEVICE_KEY_ATTESTATION_REAL} verifier denies).
     */
    public BrokerControlPlane(PeerTrustLedger ledger,
                              RemoteBridgeSessionStore store,
                              RemoteBridgeAuditSink auditSink,
                              LongSupplier clock,
                              RemoteBridgeAgentErrorLedger agentErrorLedger,
                              DeviceKeyChallengeStore deviceKeyChallengeStore,
                              TpmDeviceKeySessionEvidenceStore deviceKeyEvidenceStore) {
        if (ledger == null || store == null || auditSink == null || clock == null) {
            throw new IllegalArgumentException("ledger, store, auditSink and clock are required");
        }
        this.ledger = ledger;
        this.store = store;
        this.auditSink = auditSink;
        this.clock = clock;
        this.agentErrorLedger = agentErrorLedger == null ? new RemoteBridgeAgentErrorLedger(0) : agentErrorLedger;
        this.deviceKeyChallengeStore = deviceKeyChallengeStore;
        this.deviceKeyEvidenceStore = deviceKeyEvidenceStore;
    }

    @Override
    public void onAgentHello(PeerIdentity peer, RemoteBridgeMessages.AgentHello hello) {
        PeerTrustLedger.PeerTrust trust = ledger.record(peer, hello, clock.getAsLong());
        lastHelloByPeer.put(peer.transportPeerKey(), hello);
        recordBestEffort("ledger", "HELLO_VERIFIED:cert=" + trust.certTrusted()
                + ",attestation=" + trust.attestationVerified() + ",device=" + trust.deviceTrusted());
    }

    @Override
    public void onHeartbeat(PeerIdentity peer) {
        RemoteBridgeMessages.AgentHello hello = lastHelloByPeer.get(peer.transportPeerKey());
        if (hello == null) {
            return;
        }
        // A heartbeat is not authority and does not create trust. It only re-runs the same fail-closed
        // verifier path over the last AgentHello evidence while bound to the current authenticated mTLS peer,
        // keeping long-lived control streams from losing trust solely because the initial AgentHello aged out.
        ledger.record(peer, hello, clock.getAsLong());
    }

    @Override
    public void onConsentResult(PeerIdentity peer, RemoteBridgeMessages.ConsentResult result) {
        long now = clock.getAsLong();
        RemoteBridgeSession session = store.bySessionId(result.sessionId()).orElse(null);
        if (session == null) {
            recordBestEffort(result.sessionId(), "CONSENT_REFUSED:unknown-session");
            return;
        }
        if (!session.transportPeerKey().equals(peer.transportPeerKey())) {
            // a peer may only answer for ITS OWN session — never another device's
            recordBestEffort(session.sessionId(), "CONSENT_REFUSED:wrong-peer");
            return;
        }
        if (now >= session.promptExpiryEpochMillis()) {
            recordBestEffort(session.sessionId(), "CONSENT_REFUSED:late");
            return;
        }
        if (!result.granted()) {
            if (session.transition(Event.CONSENT_DENIED).accepted()) {
                store.evictIfTerminal(session.sessionId());
                evictDeviceKeySession(session.sessionId(), session.transportPeerKey()); // F1 terminal cleanup
                terminateViewOnly(session.sessionId()); // #1580 — no stale fanout after consent denied
                recordBestEffort(session.sessionId(), "CONSENT_DENIED");
            } else {
                recordBestEffort(session.sessionId(), "CONSENT_REFUSED:not-pending");
            }
            return;
        }
        if (result.expiryEpochMillis() <= now) {
            // proto3's default 0 (or any not-in-the-future expiry) must NEVER escalate to the full broker
            // window — a malformed grant is no grant (Codex 019ebbfa post-impl P1)
            recordBestEffort(session.sessionId(), "CONSENT_REFUSED:invalid-expiry");
            return;
        }
        // the machine accepts CONSENT_GRANTED only from CONSENT_PENDING — duplicates/replays refuse here
        if (!session.transition(Event.CONSENT_GRANTED).accepted()) {
            recordBestEffort(session.sessionId(), "CONSENT_REFUSED:not-pending");
            return;
        }
        refreshTrustFromLastHello(peer, session.sessionId(), now);
        session.grantConsent(true, result.expiryEpochMillis()); // clamped to the broker prompt window
        recordBestEffort(session.sessionId(), "CONSENT_GRANTED:lease-until="
                + session.lease().expiryEpochMillis());
        // D10.1 (#634, Codex 019ec25c): a VALID granted consent (peer/expiry/pending already checked) moves the
        // session to ACTIVE so the operator transport can drive operations. ACTIVE is TRANSPORT/session READINESS
        // ONLY — NOT authority: an operation still goes through owner-grant + step-up + trust evidence + duress +
        // capability policy at PERMIT time. Without this the session stalls at CONSENT_GRANTED and isActive() is
        // false, so the broker can never PERMIT a legitimately-consented session.
        if (session.transition(Event.ACTIVATE).accepted()) {
            recordBestEffort(session.sessionId(), "ACTIVE:lease-until=" + session.lease().expiryEpochMillis());
        } else {
            recordBestEffort(session.sessionId(), "ACTIVATE_REFUSED:not-consent-granted");
        }
    }

    @Override
    public void onAuditEvent(PeerIdentity peer, RemoteBridgeMessages.AuditEvent event) {
        if (!INBOUND_AUDIT_ALLOWLIST.contains(event.eventType())) {
            recordBestEffort(event.sessionId(), "AGENT_AUDIT_REFUSED:" + safeType(event.eventType()));
            return;
        }
        recordBestEffort(event.sessionId(), "AGENT:" + event.eventType());
        // ConsentLease's contract counts a persistent indicator loss as a local abort (the endpoint user can
        // no longer SEE that support is active) — both events kill, fail-closed (Codex 019ebbfa post-impl P1)
        if (EVENT_LOCAL_ABORT.equals(event.eventType()) || EVENT_INDICATOR_LOST.equals(event.eventType())) {
            String cause = EVENT_LOCAL_ABORT.equals(event.eventType()) ? "local-abort" : "indicator-lost";
            store.bySessionId(event.sessionId())
                    .filter(session -> session.transportPeerKey().equals(peer.transportPeerKey()))
                    .ifPresent(session -> {
                        session.abortLeaseLocally();
                        if (session.transition(Event.LOCAL_ABORT).accepted()) {
                            store.evictIfTerminal(session.sessionId());
                            evictDeviceKeySession(session.sessionId(), session.transportPeerKey()); // F1 cleanup
                            terminateViewOnly(session.sessionId()); // #1580 — no stale fanout after abort/indicator-loss
                            recordBestEffort(session.sessionId(), "KILLED:" + cause);
                        }
                    });
        }
    }

    @Override
    public void onAgentErrorFrame(PeerIdentity peer, RemoteBridgeMessages.AgentErrorFrame error) {
        String sessionId = error.sessionId() == null || error.sessionId().isBlank() ? "ledger" : error.sessionId();
        String eventType = "AGENT_ERROR:" + safeType(error.code()) + ":retryable=" + error.retryable();
        agentErrorLedger.record(peer, error, clock.getAsLong());
        recordBestEffort(sessionId, eventType);
        log.warn("remote-bridge agent error frame peer={} session={} code={} retryable={}",
                peer.transportPeerKey(), sessionId, safeType(error.code()), error.retryable());
    }

    /**
     * Faz 22.6 #548 slice-1 step-5 — the REAL consumer of the canonical TPM-native device-key attestation
     * (overrides the T-2b inert no-op). It ABSORBS the response into broker correlation state only; it confers
     * NO trust by itself — the {@code DEVICE_KEY_ATTESTATION_REAL} verifier re-derives every authoritative fact
     * at PERMIT time. Fail-closed at every step (a drop never throws, never partially stores):
     * <ol>
     *   <li>not wired (no challenge/evidence store) → inert;</li>
     *   <li>shape-decode the response FIRST (no state change on garbage);</li>
     *   <li>single-use, peer-bound, TTL-bound {@link DeviceKeyChallengeStore#consume} of the broker challenge it
     *       answers (unknown/expired/already-consumed/wrong-peer all drop uniformly — no oracle);</li>
     *   <li>store the evidence ONLY for the peer's live, non-terminal session — keyed by
     *       {@code (sessionId, transportPeerKey)}, with the freshness window inherited from the challenge expiry.</li>
     * </ol>
     */
    @Override
    public void onDeviceKeyAttestationResponse(PeerIdentity peer,
                                               RemoteBridgeMessages.DeviceKeyAttestationResponse response) {
        if (deviceKeyChallengeStore == null || deviceKeyEvidenceStore == null) {
            return; // step-5b issuance not wired → inert (fail-closed: no evidence ever stored)
        }
        long now = clock.getAsLong();
        Optional<TpmDeviceKeySessionAttestation> mapped = DeviceKeySessionAttestationMapper.map(response);
        if (mapped.isEmpty()) {
            recordBestEffort("ledger", "DEVICE_KEY_RESPONSE_DROPPED:unmappable");
            return; // unmappable shape → no challenge is consumed (no state change on garbage)
        }
        // Resolve the peer's live session FIRST, then consume the challenge bound to THAT session (Codex F1): a
        // challenge issued for a now-gone session can never be redeemed against a new session the same reconnected
        // peer later opened — the session-bound consume keeps it for its own (gone) session and drops here.
        RemoteBridgeSession session = store.liveByPeer(peer.transportPeerKey()).orElse(null);
        if (session == null) {
            recordBestEffort("ledger", "DEVICE_KEY_RESPONSE_DROPPED:no-live-session");
            return; // no orphan evidence — only a live, non-terminal session's peer gets an entry
        }
        Optional<RemoteBridgeMessages.DeviceKeyChallenge> consumed = deviceKeyChallengeStore.consume(
                response.challengeId(), peer.transportPeerKey(), session.sessionId(), now);
        if (consumed.isEmpty()) {
            recordBestEffort(session.sessionId(), "DEVICE_KEY_RESPONSE_DROPPED:no-live-challenge"
                    + ",response_hash=" + shortAuditHash(response.challengeId())
                    + ",session_challenge_hash=" + shortAuditHash(session.deviceKeyChallengeId()));
            return; // unknown / expired / already-consumed / wrong-peer / wrong-session — uniform drop, no oracle
        }
        // INCARNATION guard (Codex REVISE F1-2): the response must answer the challenge THIS live session
        // incarnation currently expects — so a response for a superseded/prior challenge of a reused id is dropped
        // and never pollutes the slot. (The verifier independently re-checks this incarnation at PERMIT time.)
        if (!response.challengeId().equals(session.deviceKeyChallengeId())) {
            recordBestEffort(session.sessionId(), "DEVICE_KEY_RESPONSE_DROPPED:stale-incarnation"
                    + ",response_hash=" + shortAuditHash(response.challengeId())
                    + ",session_challenge_hash=" + shortAuditHash(session.deviceKeyChallengeId()));
            return;
        }
        RemoteBridgeMessages.DeviceKeyChallenge challenge = consumed.get();
        deviceKeyEvidenceStore.store(session.sessionId(), peer.transportPeerKey(),
                new TpmDeviceKeySessionEvidenceStore.StoredEvidence(
                        challenge, mapped.get(), now, challenge.expiresAtEpochMillis()));
        recordBestEffort(session.sessionId(),
                "DEVICE_KEY_EVIDENCE_STORED:challenge_hash=" + shortAuditHash(challenge.challengeId()));
    }

    /**
     * Clear any pending device-key challenge + stored session evidence for a TERMINATING session (Codex REVISE
     * F1): the broker {@code sessionId} is client-supplied + reusable, so on terminal the prior session's
     * device-key state MUST be dropped or a reused id could inherit it. Null-safe (a no-op when the device-key
     * path is not wired).
     */
    private void evictDeviceKeySession(String sessionId, String transportPeerKey) {
        if (deviceKeyChallengeStore != null) {
            deviceKeyChallengeStore.evictSession(sessionId, transportPeerKey);
        }
        if (deviceKeyEvidenceStore != null) {
            deviceKeyEvidenceStore.evict(sessionId, transportPeerKey);
        }
    }

    /**
     * Faz 22.6 #1580 — wire the VIEW_ONLY lifecycle seam (server config, post-construction) so agent-driven
     * terminals also terminate the view-only surface. Optional/nullable; existing wiring/tests unaffected.
     */
    public void configureViewOnlyLifecycle(ViewOnlySessionLifecycle lifecycle) {
        this.viewOnlyLifecycle = lifecycle;
    }

    private void terminateViewOnly(String sessionId) {
        ViewOnlySessionLifecycle lifecycle = this.viewOnlyLifecycle;
        if (lifecycle != null) {
            lifecycle.terminate(sessionId);
        }
    }

    /**
     * Inbound absorption is best-effort on the recorder: a recording failure must never make the broker
     * IGNORE a consent denial or a local abort (the safe outcome proceeds). The durable-record-BEFORE-permit
     * rule lives in {@code RemoteBridgeBroker} where authority is actually issued.
     */
    private void recordBestEffort(String sessionId, String eventType) {
        try {
            auditSink.record(new RemoteBridgeMessages.AuditEvent(sessionId, eventType, "", clock.getAsLong()));
        } catch (RuntimeException e) {
            log.warn("remote-bridge inbound audit write failed (continuing fail-safe): {}", eventType);
        }
    }

    private static String safeType(String type) {
        if (type == null) {
            return "null";
        }
        String cleaned = type.replaceAll("[^A-Za-z0-9_:-]", "_");
        return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
    }

    private static String shortAuditHash(String value) {
        if (value == null || value.isBlank()) {
            return "blank";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private void refreshTrustFromLastHello(PeerIdentity peer, String sessionId, long now) {
        RemoteBridgeMessages.AgentHello hello = lastHelloByPeer.get(peer.transportPeerKey());
        if (hello == null) {
            return;
        }
        // A valid consent result is an authenticated inbound control-plane event from the same mTLS peer as the
        // session. It does not create trust by itself; it only re-runs the same fail-closed verifier path over
        // the peer's cached AgentHello evidence so legacy agents without HEARTBEAT frames do not lose trust
        // solely because the operator took longer than the ledger freshness TTL to approve and run an operation.
        PeerTrustLedger.PeerTrust trust = ledger.record(peer, hello, now);
        // Session-scoped hello re-verification signal (Faz 22.6 #1580). onAgentHello() records the peer-level
        // HELLO_VERIFIED to the "ledger" sink before any session exists, so a session-filtered audit stream never
        // observes it. This consent-time refresh re-runs the same fail-closed verifier over the peer's cached
        // AgentHello while bound to the same authenticated mTLS peer as the session, and duplicate/replayed consent
        // is refused before this call — so a session-scoped HELLO_VERIFIED here is a real, per-session
        // re-verification result, not a retroactive copy of the peer-level ledger event (which is retained for
        // peer-level diagnostics). Trust values mirror CONSENT_TRUST_REFRESHED so a session stream is self-consistent.
        recordBestEffort(sessionId, "HELLO_VERIFIED:source=consent-refresh,cert=" + trust.certTrusted()
                + ",attestation=" + trust.attestationVerified() + ",device=" + trust.deviceTrusted());
        recordBestEffort(sessionId, "CONSENT_TRUST_REFRESHED:cert=" + trust.certTrusted()
                + ",attestation=" + trust.attestationVerified() + ",device=" + trust.deviceTrusted());
    }
}

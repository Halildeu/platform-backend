package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeAuditSink;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeSessionStateMachine.Event;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages;
import com.example.endpointadmin.remoteaccess.bridge.server.ControlPlaneHandler;
import com.example.endpointadmin.remoteaccess.bridge.server.PeerIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
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

    /** Agent-originated audit metadata the broker accepts; anything else is refused (audited, dropped). */
    private static final Set<String> INBOUND_AUDIT_ALLOWLIST = Set.of(
            EVENT_LOCAL_ABORT,
            "AGENT_INDICATOR_SHOWN",   // the visible 'remote-support active' indicator came up (D10-6)
            "AGENT_INDICATOR_LOST");   // ...or could not be kept on screen

    private final PeerTrustLedger ledger;
    private final RemoteBridgeSessionStore store;
    private final RemoteBridgeAuditSink auditSink;
    private final LongSupplier clock;

    public BrokerControlPlane(PeerTrustLedger ledger,
                              RemoteBridgeSessionStore store,
                              RemoteBridgeAuditSink auditSink,
                              LongSupplier clock) {
        if (ledger == null || store == null || auditSink == null || clock == null) {
            throw new IllegalArgumentException("ledger, store, auditSink and clock are required");
        }
        this.ledger = ledger;
        this.store = store;
        this.auditSink = auditSink;
        this.clock = clock;
    }

    @Override
    public void onAgentHello(PeerIdentity peer, RemoteBridgeMessages.AgentHello hello) {
        PeerTrustLedger.PeerTrust trust = ledger.record(peer, hello, clock.getAsLong());
        recordBestEffort("ledger", "HELLO_VERIFIED:cert=" + trust.certTrusted()
                + ",attestation=" + trust.attestationVerified() + ",device=" + trust.deviceTrusted());
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
                recordBestEffort(session.sessionId(), "CONSENT_DENIED");
            } else {
                recordBestEffort(session.sessionId(), "CONSENT_REFUSED:not-pending");
            }
            return;
        }
        // the machine accepts CONSENT_GRANTED only from CONSENT_PENDING — duplicates/replays refuse here
        if (!session.transition(Event.CONSENT_GRANTED).accepted()) {
            recordBestEffort(session.sessionId(), "CONSENT_REFUSED:not-pending");
            return;
        }
        session.grantConsent(true, result.expiryEpochMillis()); // clamped to the broker prompt window
        recordBestEffort(session.sessionId(), "CONSENT_GRANTED:lease-until="
                + session.lease().expiryEpochMillis());
    }

    @Override
    public void onAuditEvent(PeerIdentity peer, RemoteBridgeMessages.AuditEvent event) {
        if (!INBOUND_AUDIT_ALLOWLIST.contains(event.eventType())) {
            recordBestEffort(event.sessionId(), "AGENT_AUDIT_REFUSED:" + safeType(event.eventType()));
            return;
        }
        recordBestEffort(event.sessionId(), "AGENT:" + event.eventType());
        if (EVENT_LOCAL_ABORT.equals(event.eventType())) {
            store.bySessionId(event.sessionId())
                    .filter(session -> session.transportPeerKey().equals(peer.transportPeerKey()))
                    .ifPresent(session -> {
                        session.abortLeaseLocally();
                        if (session.transition(Event.LOCAL_ABORT).accepted()) {
                            store.evictIfTerminal(session.sessionId());
                            recordBestEffort(session.sessionId(), "KILLED:local-abort");
                        }
                    });
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
}

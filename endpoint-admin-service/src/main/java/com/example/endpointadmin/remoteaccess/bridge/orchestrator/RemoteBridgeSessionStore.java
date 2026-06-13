package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeSessionStateMachine.Event;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeSessionStateMachine.State;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages;
import com.example.endpointadmin.remoteaccess.bridge.contract.WireContract;
import com.example.endpointadmin.remoteaccess.bridge.server.PeerIdentity;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Faz 22.6 T-4a-i (Codex 019ebbfa P2) — the broker's IN-MEMORY session registry. Pilot scope: a single
 * broker replica; a broker restart drops every live session (fail-safe — agents reconnect and sessions must
 * be re-requested; the durable audit trail lives in the recorder, never here). ONE non-terminal session per
 * authenticated peer ({@code killPeer} is peer-scoped, so concurrent sessions on one peer would make a kill
 * ambiguous — refused at open).
 *
 * <p>{@link #open} walks the new session through the state machine
 * ({@code DISABLED→IDLE_CONNECTED→SESSION_REQUESTED→CONSENT_PENDING}) — every step machine-accepted, never
 * hand-set — and leaves it waiting for the endpoint user's consent.
 */
public final class RemoteBridgeSessionStore {

    private final Map<String, RemoteBridgeSession> bySessionId = new ConcurrentHashMap<>();
    private final Map<String, String> sessionIdByPeer = new ConcurrentHashMap<>();

    /** Why an open was refused (audit detail — never attacker-echoed content). */
    public sealed interface OpenResult permits Opened, Refused {
    }

    public record Opened(RemoteBridgeSession session) implements OpenResult {
    }

    public record Refused(String reason) implements OpenResult {
    }

    /**
     * Open a session for an operator request against an authenticated peer, leaving it CONSENT_PENDING.
     * Fail-closed: invalid request shape, a non-canonical operator tenant, a duplicate session id, or an
     * existing non-terminal session on the same peer all refuse.
     *
     * <p>Faz 22.6 slice-4c-2b-2b hardening (Codex 019ebe06): {@code operatorTenantId} is the tenancy boundary
     * the follow-up ownership guard ({@code RemoteBridgeOperatorController.ownedSession}) compares against, so a
     * blank/non-canonical tenant would silently weaken that guard. This is a PUBLIC orchestrator chokepoint —
     * EVERY session creation goes through here — so the canonical-UUID invariant is enforced at the store, not
     * trusted to the (sole, today) caller. The controller already passes {@code UUID.toString()} (canonical
     * lowercase), so the valid path is unchanged; only a non-canonical tenant is newly refused.
     */
    public OpenResult open(RemoteBridgeMessages.SessionRequest request,
                           PeerIdentity peer,
                           String operatorTenantId,
                           String operatorDisplayName,
                           long promptExpiryEpochMillis,
                           long nowEpochMillis) {
        if (!WireContract.isValid(request) || peer == null) {
            return new Refused("invalid-session-request");
        }
        if (!isCanonicalUuid(operatorTenantId)) {
            return new Refused("invalid-operator-tenant"); // fail-closed: no session is created
        }
        if (promptExpiryEpochMillis <= nowEpochMillis) {
            return new Refused("prompt-expiry-not-in-future");
        }
        RemoteBridgeSession session = new RemoteBridgeSession(request.sessionId(), peer.transportPeerKey(),
                request.deviceId(), request.operatorSubject(), operatorTenantId,
                operatorDisplayName == null || operatorDisplayName.isBlank() ? request.operatorSubject()
                        : operatorDisplayName,
                request.requestedCapabilities(), promptExpiryEpochMillis, nowEpochMillis, State.DISABLED);
        // machine-accepted walk to CONSENT_PENDING — any refusal here is a programming error surfaced loudly
        for (Event event : new Event[] {Event.ENABLE, Event.REQUEST_SESSION, Event.PROMPT_CONSENT}) {
            if (!session.transition(event).accepted()) {
                return new Refused("state-machine-refused-" + event.name().toLowerCase());
            }
        }
        if (bySessionId.putIfAbsent(request.sessionId(), session) != null) {
            return new Refused("duplicate-session-id");
        }
        String previousSessionId = sessionIdByPeer.compute(peer.transportPeerKey(), (key, existingId) -> {
            if (existingId != null) {
                RemoteBridgeSession existing = bySessionId.get(existingId);
                if (existing != null && !existing.isTerminal()) {
                    return existingId; // keep the live one — the new open loses
                }
            }
            return request.sessionId();
        });
        if (!request.sessionId().equals(previousSessionId)) {
            bySessionId.remove(request.sessionId());
            return new Refused("peer-already-has-live-session");
        }
        return new Opened(session);
    }

    public Optional<RemoteBridgeSession> bySessionId(String sessionId) {
        return Optional.ofNullable(bySessionId.get(sessionId));
    }

    /** The peer's single live session, if any (terminal sessions are not returned). */
    public Optional<RemoteBridgeSession> liveByPeer(String transportPeerKey) {
        String sessionId = sessionIdByPeer.get(transportPeerKey);
        if (sessionId == null) {
            return Optional.empty();
        }
        RemoteBridgeSession session = bySessionId.get(sessionId);
        return session == null || session.isTerminal() ? Optional.empty() : Optional.of(session);
    }

    /** Drop a terminal session's registrations (the audit trail is the recorder's, never this map's). */
    public void evictIfTerminal(String sessionId) {
        RemoteBridgeSession session = bySessionId.get(sessionId);
        if (session != null && session.isTerminal()) {
            bySessionId.remove(sessionId, session);
            sessionIdByPeer.remove(session.transportPeerKey(), sessionId);
        }
    }

    public int liveCount() {
        return (int) bySessionId.values().stream().filter(s -> !s.isTerminal()).count();
    }

    /**
     * Strict canonical-UUID check: a {@code UUID.fromString} round-trip that must reproduce the input EXACTLY.
     * {@code UUID.fromString} is lenient (accepts uppercase hex and other non-canonical spellings); requiring
     * {@code toString().equals(value)} pins the input to the canonical 8-4-4-4-12 lowercase form the controller
     * emits via {@code UUID.toString()}. Null/blank fail-closed.
     */
    private static boolean isCanonicalUuid(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException notAUuid) {
            return false;
        }
    }
}

package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeAuditSink;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeBroker;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeBroker.BrokerOutcome;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeSessionStateMachine.Event;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeSessionStateMachine.State;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeTrustEvidence;
import com.example.endpointadmin.remoteaccess.bridge.contract.OperationPermit;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.AuditEvent;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.ConsentPrompt;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.DeviceKeyChallenge;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.OperationDispatch;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.OperationRequest;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.SessionRequest;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeSessionStore.OpenResult;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeSessionStore.Opened;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeSessionStore.Refused;
import com.example.endpointadmin.remoteaccess.bridge.server.ControlStreamRegistry;
import com.example.endpointadmin.remoteaccess.bridge.server.PeerIdentity;
import com.example.endpointadmin.remoteaccess.bridge.server.viewonly.ViewOnlySessionLifecycle;
import com.example.endpointadmin.remoteaccess.bridge.server.viewonly.ViewOnlyStreamAuthorizationRegistry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Faz 22.6 T-4a-ii slice-4b-2 (Codex 019ebd7f) — the operator-side orchestration: drives one operator
 * {@link OperationRequest} through the broker and routes the {@link BrokerOutcome} to the transport. This is
 * the seam that finally connects the broker (slice-3c) to the agent push primitives (slice-4a). Issues NO
 * authority itself — the broker is the only thing that mints a permit; this service only transports the
 * verdict. Disabled-by-default (the bean exists only when {@code remote-bridge.enabled=true}).
 *
 * <p><b>Pipeline:</b> a malformed request or an unknown session is REJECTED before the broker is consulted (no
 * {@code nextSeq} burned — Codex S2). Otherwise the {@link TrustEvidenceAssembler} builds the fail-closed
 * evidence, {@code broker.handle} decides, and the outcome routes:
 * <ul>
 *   <li><b>PERMIT</b> → push the signed permit on the peer's CONTROL stream; {@code transportPushed=false}
 *       when the stream is gone (the permit was durably recorded upstream, but it simply does not land — the
 *       operation does not proceed, no further action — Codex S3).</li>
 *   <li><b>KILL</b> → kill the transport stream AND drive the session state machine to a terminal state +
 *       evict it (so no ACTIVE ghost session lingers — Codex S1). A duress that is AMBIGUOUS because the
 *       transport duress-classification is not yet wired surfaces here as a KILL — fail-closed.</li>
 *   <li><b>DENY</b> → nothing pushed; the broker already recorded the denial. The session is also driven
 *       terminal + evicted so a denied policy decision cannot strand the peer slot.</li>
 * </ul>
 */
public final class RemoteBridgeOperatorService {

    /** The operator-facing outcome: the broker verdict (null when rejected pre-broker) + whether it transported. */
    public record OperatorOutcome(BrokerOutcome brokerOutcome, boolean transportPushed, String rejectReason) {
        public boolean accepted() {
            return brokerOutcome != null;
        }

        static OperatorOutcome handled(BrokerOutcome outcome, boolean transportPushed) {
            return new OperatorOutcome(outcome, transportPushed, null);
        }

        static OperatorOutcome rejected(String reason) {
            return new OperatorOutcome(null, false, reason);
        }
    }

    private final RemoteBridgeSessionStore store;
    private final TrustEvidenceAssembler assembler;
    private final RemoteBridgeBroker broker;
    private final ControlStreamRegistry registry;
    private final RemoteBridgeAuditSink auditSink;
    private final LongSupplier clock;
    private final long consentPromptTtlMillis;
    // Faz 22.6 #548 step-5b — the canonical device-key session attestation issuance. When disabled (or the store
    // is null) openSession emits NO challenge, so the DEVICE_KEY_ATTESTATION_REAL verifier has no evidence and
    // denies (fail-closed). Enabled exactly when the REAL verifier is the active device-trust basis.
    private final DeviceKeyChallengeStore deviceKeyChallengeStore;
    private final TpmDeviceKeySessionEvidenceStore deviceKeyEvidenceStore;
    private final boolean deviceKeySessionEnabled;
    private final long deviceKeyChallengeTtlMillis;
    // Faz 22.6 #1580 (Codex 019f078a + 019f0e78) — the VIEW_ONLY session lifecycle seam: authorize a stream on a
    // delivered VIEW_ONLY permit, and TERMINATE (revoke authorization + close viewers) on every operator-driven
    // terminal. Optional (null = this bridge does not run the #1580 view-only data plane) so existing wiring/tests
    // are unaffected; fail-closed (a null seam records no authorization, so the data-plane handler fans nothing
    // out). Set post-construction by the server config — this service mints no authority of its own.
    private volatile ViewOnlySessionLifecycle viewOnlyLifecycle;
    private volatile long viewOnlyStreamAuthorizationTtlMillis;

    /** Back-compat: no device-key session attestation issuance (the prior behaviour). */
    public RemoteBridgeOperatorService(RemoteBridgeSessionStore store, TrustEvidenceAssembler assembler,
                                       RemoteBridgeBroker broker, ControlStreamRegistry registry,
                                       RemoteBridgeAuditSink auditSink, LongSupplier clock,
                                       long consentPromptTtlMillis) {
        this(store, assembler, broker, registry, auditSink, clock, consentPromptTtlMillis, null, null, false, 0L);
    }

    /**
     * Faz 22.6 #548 step-5b — with device-key session attestation issuance. When {@code deviceKeySessionEnabled},
     * {@link #openSession} issues a broker-nonced challenge (TTL {@code deviceKeyChallengeTtlMillis}) on the peer's
     * CONTROL stream right after the session opens and before the consent prompt. The {@code deviceKeyEvidenceStore}
     * (nullable) lets the service clear stale device-key state on every terminal path + at a fresh open of a
     * reused {@code sessionId} (Codex REVISE F1 — the broker session id is client-supplied + reusable).
     */
    public RemoteBridgeOperatorService(RemoteBridgeSessionStore store, TrustEvidenceAssembler assembler,
                                       RemoteBridgeBroker broker, ControlStreamRegistry registry,
                                       RemoteBridgeAuditSink auditSink, LongSupplier clock,
                                       long consentPromptTtlMillis,
                                       DeviceKeyChallengeStore deviceKeyChallengeStore,
                                       TpmDeviceKeySessionEvidenceStore deviceKeyEvidenceStore,
                                       boolean deviceKeySessionEnabled, long deviceKeyChallengeTtlMillis) {
        this.store = Objects.requireNonNull(store, "store");
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.broker = Objects.requireNonNull(broker, "broker");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (consentPromptTtlMillis <= 0) {
            throw new IllegalArgumentException("consentPromptTtlMillis must be positive");
        }
        this.consentPromptTtlMillis = consentPromptTtlMillis;
        this.deviceKeySessionEnabled = deviceKeySessionEnabled;
        this.deviceKeyChallengeStore = deviceKeyChallengeStore;
        this.deviceKeyEvidenceStore = deviceKeyEvidenceStore;
        if (deviceKeySessionEnabled) {
            if (deviceKeyChallengeStore == null) {
                throw new IllegalArgumentException(
                        "device-key session attestation enabled requires a DeviceKeyChallengeStore");
            }
            if (deviceKeyChallengeTtlMillis <= 0) {
                throw new IllegalArgumentException("deviceKeyChallengeTtlMillis must be positive when enabled");
            }
        }
        this.deviceKeyChallengeTtlMillis = deviceKeyChallengeTtlMillis;
    }

    /**
     * Clear any pending device-key challenge + stored session evidence for {@code (sessionId, transportPeerKey)}
     * (Codex REVISE F1). Called on every terminal path AND before a fresh issue, so a reused {@code sessionId}
     * can never inherit a prior session's pending challenge or fresh evidence. Total + null-safe (a no-op when the
     * stores are not wired).
     */
    private void evictDeviceKeySession(String sessionId, String transportPeerKey) {
        if (deviceKeyChallengeStore != null) {
            deviceKeyChallengeStore.evictSession(sessionId, transportPeerKey);
        }
        if (deviceKeyEvidenceStore != null) {
            deviceKeyEvidenceStore.evict(sessionId, transportPeerKey);
        }
    }

    /** The outcome of opening an attended session: the session id, whether the consent prompt reached the agent. */
    public record SessionOpenOutcome(String sessionId, boolean consentPromptSent, String rejectReason) {
        public boolean opened() {
            return rejectReason == null;
        }

        static SessionOpenOutcome prompted(String sessionId) {
            return new SessionOpenOutcome(sessionId, true, null);
        }

        static SessionOpenOutcome rejected(String sessionId, String reason) {
            return new SessionOpenOutcome(sessionId, false, reason);
        }
    }

    /** Explicit operator close outcome. A failed durable close audit refuses the close and keeps the slot live. */
    public record SessionCloseOutcome(String sessionId, boolean closed, String rejectReason) {
        public boolean accepted() {
            return closed && rejectReason == null;
        }

        static SessionCloseOutcome closed(String sessionId) {
            return new SessionCloseOutcome(sessionId, true, null);
        }

        static SessionCloseOutcome rejected(String sessionId, String reason) {
            return new SessionCloseOutcome(sessionId, false, reason);
        }
    }

    /**
     * Faz 22.6 T-4a-ii slice-4b-3 (Codex 019ebd7f) — open an ATTENDED session and push the consent prompt to
     * the agent. The store walks the new session to {@code CONSENT_PENDING} itself, so the service does NOT
     * re-drive the state machine (Codex S3). The prompt TTL is config-derived, not caller-supplied (S2).
     *
     * <p><b>Fail-closed:</b> a peer with no live CONTROL stream is refused BEFORE the session is created
     * (pre-guard). If the prompt does not land despite the pre-guard (the peer dropped in the race window), the
     * just-opened session is driven terminal + evicted so no orphan {@code CONSENT_PENDING} session lingers
     * awaiting a consent that can never arrive (Codex S3).
     *
     * <p><b>Tenant invariant (slice-4c-2b-2b, Codex 019ebe06):</b> {@code operatorTenantId} is not validated
     * here — this is a PUBLIC orchestrator seam, so the canonical-UUID invariant is enforced at the store
     * chokepoint ({@link RemoteBridgeSessionStore#open}). A blank/non-canonical tenant therefore surfaces here
     * as a {@code rejected("invalid-operator-tenant")} outcome with NO session created, even for a future caller
     * that is not the controller.
     */
    public SessionOpenOutcome openSession(SessionRequest request, PeerIdentity peer, String operatorTenantId,
                                          String operatorDisplayName) {
        if (request == null || request.sessionId() == null || request.sessionId().isBlank() || peer == null) {
            return SessionOpenOutcome.rejected(request == null ? null : request.sessionId(), "malformed-request");
        }
        long now = clock.getAsLong();
        if (!registry.isConnected(peer.transportPeerKey())) {
            return SessionOpenOutcome.rejected(request.sessionId(), "peer-not-connected"); // pre-guard
        }

        OpenResult result = store.open(request, peer, operatorTenantId, operatorDisplayName,
                now + consentPromptTtlMillis, now);
        if (result instanceof Refused refused) {
            return SessionOpenOutcome.rejected(request.sessionId(), refused.reason());
        }
        RemoteBridgeSession session = ((Opened) result).session(); // ALREADY CONSENT_PENDING (store walked it)
        // #1580 — a fresh VIEW_ONLY incarnation: record this session as the incarnation token + clear stale authz
        // for this (reused) sessionId so a legitimately reused session can authorize a screen stream again.
        beginViewOnlySession(session);

        // Faz 22.6 #548 step-5b — issue the canonical device-key challenge AFTER the session exists and BEFORE the
        // consent prompt, so the agent can answer it during the consent window (the DEVICE_KEY_ATTESTATION_REAL
        // verifier reads the resulting evidence at PERMIT time). Gated: emitted only when the REAL verifier is the
        // active device-trust basis. Fail-closed: a send failure kills + evicts the just-opened session, exactly
        // like an undelivered consent prompt — no orphan session waits for a response that can never arrive.
        if (deviceKeySessionEnabled && deviceKeyChallengeStore != null) {
            // Codex REVISE F1: the broker sessionId is client-supplied + reusable — clear ANY stale challenge /
            // evidence for THIS (sessionId, peer) before issuing, so a reused id can never inherit prior state.
            evictDeviceKeySession(session.sessionId(), peer.transportPeerKey());
            DeviceKeyChallenge challenge = deviceKeyChallengeStore.issue(
                    session.sessionId(), peer.transportPeerKey(), deviceKeyChallengeTtlMillis, now);
            // bind THIS incarnation to the issued challenge BEFORE sending (Codex REVISE F1-2 + AGREE note): the
            // verifier trusts only evidence whose challengeId equals this, so a stale prior-incarnation response
            // for a reused id cannot pass; binding pre-send also means a very-fast legitimate response is never
            // dropped as stale-incarnation (no availability false-negative window). A send failure below kills +
            // evicts the session, so the binding on a terminal session is moot.
            session.bindDeviceKeyChallenge(challenge.challengeId());
            recordDeviceKeyChallengeBestEffort(session.sessionId(), "DEVICE_KEY_CHALLENGE_ISSUED",
                    challenge.challengeId(), now);
            if (!registry.sendDeviceKeyChallenge(peer.transportPeerKey(), session.sessionId(), challenge, now)) {
                recordDeviceKeyChallengeBestEffort(session.sessionId(), "DEVICE_KEY_CHALLENGE_NOT_DELIVERED",
                        challenge.challengeId(), now);
                evictDeviceKeySession(session.sessionId(), peer.transportPeerKey()); // clear the undelivered challenge
                session.transition(Event.KILL);
                store.evictIfTerminal(session.sessionId());
                terminateViewOnly(session.sessionId()); // #1580 — re-fail-close (beginViewOnlySession ran above)
                return SessionOpenOutcome.rejected(session.sessionId(), "device-key-challenge-not-delivered");
            }
            recordDeviceKeyChallengeBestEffort(session.sessionId(), "DEVICE_KEY_CHALLENGE_SENT",
                    challenge.challengeId(), now);
        }

        ConsentPrompt prompt = new ConsentPrompt(session.sessionId(), session.operatorDisplayName(),
                request.reason(), session.requestedCapabilities(), session.promptExpiryEpochMillis());
        boolean sent = registry.sendConsentPrompt(peer.transportPeerKey(), prompt, now);
        if (!sent) {
            // the peer dropped between the pre-guard and the send → don't leave an orphan CONSENT_PENDING session
            evictDeviceKeySession(session.sessionId(), peer.transportPeerKey()); // clear any device-key state too
            session.transition(Event.KILL);
            store.evictIfTerminal(session.sessionId());
            terminateViewOnly(session.sessionId()); // #1580 — re-fail-close (beginViewOnlySession ran above)
            return SessionOpenOutcome.rejected(session.sessionId(), "consent-prompt-not-delivered");
        }
        return SessionOpenOutcome.prompted(session.sessionId());
    }

    /**
     * Close an already ACTIVE attended session after the operator has finished the approved operation. The
     * durable session recorder is the authority boundary: if the close event cannot be recorded, the session is
     * deliberately left live so a cleanup path cannot silently bypass the audit chain.
     */
    public SessionCloseOutcome closeSession(String sessionId, String operatorSubject) {
        if (sessionId == null || sessionId.isBlank()) {
            return SessionCloseOutcome.rejected(sessionId, "malformed-request");
        }
        if (operatorSubject == null || operatorSubject.isBlank()) {
            return SessionCloseOutcome.rejected(sessionId, "malformed-operator");
        }
        long now = clock.getAsLong();
        RemoteBridgeSession session = store.bySessionId(sessionId).orElse(null);
        if (session == null) {
            return SessionCloseOutcome.rejected(sessionId, "unknown-session");
        }
        synchronized (session) {
            if (session.isTerminal()) {
                store.evictIfTerminal(sessionId);
                return SessionCloseOutcome.rejected(sessionId, "session-not-live");
            }
            State state = session.state();
            if (!state.isActive() && state != State.REVOKING) {
                return SessionCloseOutcome.rejected(sessionId, "session-close-refused");
            }
            try {
                auditSink.record(new AuditEvent(session.sessionId(), "SESSION_CLOSE:OPERATOR",
                        operatorSubjectAuditHash(operatorSubject), now));
            } catch (RuntimeException recordingFailure) {
                return SessionCloseOutcome.rejected(sessionId, "recording-failed");
            }
            if (!session.transition(Event.CLOSE).accepted()) {
                return SessionCloseOutcome.rejected(sessionId, "session-close-refused");
            }
        }
        store.evictIfTerminal(sessionId);
        evictDeviceKeySession(sessionId, session.transportPeerKey()); // F1: no stale state for a reused sessionId
        terminateViewOnly(sessionId); // #1580 — no stale fanout grant after operator close
        return SessionCloseOutcome.closed(sessionId);
    }

    private static String operatorSubjectAuditHash(String operatorSubject) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(operatorSubject.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private void recordDeviceKeyChallengeBestEffort(String sessionId, String eventType, String challengeId, long now) {
        try {
            auditSink.record(new AuditEvent(sessionId,
                    eventType + ":challenge_hash=" + shortAuditHash(challengeId), "", now));
        } catch (RuntimeException ignored) {
            // Diagnostic evidence must not alter the consent/session fail-closed path.
        }
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

    /**
     * Faz 22.6 #548 evidence seam — record the verifier's raw device-trust verdict before the policy engine can
     * legitimately fail closed on duress, step-up, or another earlier gate. This does not mint authority and does not
     * change the broker decision; it only makes the hardware-key verifier outcome auditable for acceptance review.
     */
    private void recordDeviceTrustDecisionBestEffort(RemoteBridgeSession session, RemoteBridgeTrustEvidence evidence,
                                                     long now) {
        String eventType = "DEVICE_TRUST_DECISION:trusted=" + evidence.deviceTrustDecisionTrusted()
                + ",basis=" + safeAuditToken(evidence.deviceTrustDecisionBasis().name(), "NONE")
                + ",effective_trusted=" + evidence.deviceTrusted()
                + ",effective_basis=" + safeAuditToken(evidence.deviceTrustBasis().name(), "NONE")
                + ",identity=" + evidence.deviceTrustIdentitiesConsistent()
                + ",reason=" + safeAuditToken(evidence.deviceTrustDecisionReason(), "device-untrusted");
        try {
            auditSink.record(new AuditEvent(session.sessionId(), eventType, "", now));
        } catch (RuntimeException ignored) {
            // Diagnostic evidence must not weaken or alter the fail-closed broker verdict.
        }
    }

    private static String safeAuditToken(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String canonical = value.trim();
        return canonical.matches("^[A-Za-z0-9_.:-]{1,96}$") ? canonical : fallback;
    }

    /**
     * Faz 22.6 #1580 — wire the VIEW_ONLY session lifecycle seam (server config, post-construction). When set, a
     * successfully pushed VIEW_ONLY {@code SCREEN_VIEW} permit records a stream authorization keyed by
     * {@code (sessionId, operationId)} bound to the agent transport peer; every session-terminal path terminates
     * it (revoke authorization + close viewers).
     *
     * @param lifecycle the VIEW_ONLY lifecycle seam (nullable to clear the wiring)
     * @param ttlMillis optional extra cap on the authorization lifetime; {@code <= 0} = bind to permit expiry only
     */
    public void configureViewOnlyStreamAuthorization(ViewOnlySessionLifecycle lifecycle, long ttlMillis) {
        this.viewOnlyLifecycle = lifecycle;
        this.viewOnlyStreamAuthorizationTtlMillis = Math.max(0L, ttlMillis);
    }

    private void authorizeViewOnlyStream(RemoteBridgeSession session, OperationPermit permit, long now) {
        ViewOnlySessionLifecycle lifecycle = this.viewOnlyLifecycle;
        if (lifecycle == null || permit == null) {
            return;
        }
        long expiry = permit.expiresAtEpochMillis();
        long ttl = this.viewOnlyStreamAuthorizationTtlMillis;
        if (ttl > 0) {
            expiry = Math.min(expiry, now + ttl);
        }
        // streamId == permit.operationId — the agent rides the SCREEN_VIEW operation id as the screen DATA stream id
        // the session object IS the incarnation token: a late authorize after this incarnation terminated or was
        // replaced by a reopen carries a stale token and is refused by the registry (no stale fanout grant).
        lifecycle.authorizeStream(session, new ViewOnlyStreamAuthorizationRegistry.Authorization(
                permit.sessionId(), permit.operationId(), session.transportPeerKey(),
                permit.operatorSubject(), permit.deviceId(), expiry));
    }

    private void terminateViewOnly(String sessionId) {
        ViewOnlySessionLifecycle lifecycle = this.viewOnlyLifecycle;
        if (lifecycle != null) {
            lifecycle.terminate(sessionId);
        }
    }

    private void beginViewOnlySession(RemoteBridgeSession session) {
        ViewOnlySessionLifecycle lifecycle = this.viewOnlyLifecycle;
        if (lifecycle != null) {
            // fresh incarnation: record THIS session object as the incarnation token + clear any stale authz for
            // a reused sessionId, so only an authorize for this incarnation is accepted.
            lifecycle.beginSession(session.sessionId(), session);
        }
    }

    public OperatorOutcome handleOperationRequest(OperationRequest request) {
        if (request == null || request.sessionId() == null || request.sessionId().isBlank()) {
            return OperatorOutcome.rejected("malformed-request");
        }
        long now = clock.getAsLong();
        RemoteBridgeSession session = store.bySessionId(request.sessionId()).orElse(null);
        if (session == null) {
            return OperatorOutcome.rejected("unknown-session"); // no broker call, no seq burned (Codex S2)
        }

        RemoteBridgeTrustEvidence evidence = assembler.assemble(session, now);
        recordDeviceTrustDecisionBestEffort(session, evidence, now);
        // nextSeq() consumed ONLY here, at the broker boundary, after the session is found (Codex S2)
        BrokerOutcome outcome = broker.handle(request, evidence, session.state(), session.nextSeq(), now);

        return switch (outcome.kind()) {
            case PERMIT -> {
                OperationPermit permit = outcome.permit();
                boolean pushed;
                if (permit.capability() == RemoteSessionCapability.CONSTRAINED_PTY) {
                    // CONSTRAINED_PTY carries the plaintext command alongside the signed permit
                    // (OperationDispatch). The command is bound to the SIGNED permit by the hash (the agent
                    // re-derives CanonicalCommand.hash == permit.commandHash) and is never trusted raw.
                    // Fail-closed UPSTREAM (Codex 019ecd07): a PTY permit without its command is a defect —
                    // push nothing rather than a command-less dispatch the agent would reject anyway.
                    String commandLine = request.commandLine();
                    pushed = commandLine != null && !commandLine.isBlank()
                            && registry.sendOperationDispatch(session.transportPeerKey(),
                                    new OperationDispatch(permit, commandLine), now);
                } else {
                    // VIEW_ONLY (and any non-command capability) carries no command — push the bare signed permit.
                    pushed = registry.sendOperationPermit(session.transportPeerKey(), permit, now);
                }
                if (pushed && permit.capability() == RemoteSessionCapability.VIEW_ONLY) {
                    // #1580 — a delivered VIEW_ONLY permit authorizes the matching screen DATA stream for fanout
                    // (streamId == operationId), bound to this agent peer and the permit's expiry. Fail-closed: a
                    // permit that did NOT land authorizes nothing.
                    authorizeViewOnlyStream(session, permit, now);
                }
                // The PERMIT branch only fires under a full policy pass, exercised at the owner-gated real-PERMIT
                // e2e once the B1.4d / step-up trust roots land — forcing a PERMIT here would manufacture trust
                // the system does not have (the e2e is deliberately PERMIT-agnostic).
                yield OperatorOutcome.handled(outcome, pushed);
            }
            case KILL -> {
                registry.killPeer(session.transportPeerKey(), request.sessionId(),
                        outcome.kill().killReason(), now);
                // Codex S1: a transport kill alone leaves an ACTIVE ghost — drive the state machine terminal + evict
                session.transition(Event.KILL);
                store.evictIfTerminal(request.sessionId());
                evictDeviceKeySession(request.sessionId(), session.transportPeerKey()); // F1 cleanup
                terminateViewOnly(request.sessionId()); // #1580 — no stale fanout grant after kill
                yield OperatorOutcome.handled(outcome, false);
            }
            case DENY -> {
                // Broker already recorded the denial; do not push anything to the agent. Still close the
                // operator session locally so policy DENY cannot leave an ACTIVE ghost that blocks a retry.
                session.transition(Event.CLOSE);
                store.evictIfTerminal(request.sessionId());
                evictDeviceKeySession(request.sessionId(), session.transportPeerKey()); // F1 cleanup
                terminateViewOnly(request.sessionId()); // #1580 — no stale fanout grant after deny
                yield OperatorOutcome.handled(outcome, false);
            }
        };
    }
}

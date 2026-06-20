package com.example.endpointadmin.remoteaccess.bridge;

import com.example.endpointadmin.remoteaccess.SessionRecorder;
import com.example.endpointadmin.remoteaccess.SessionRecordingChain.RecordKind;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.AuditEvent;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * Faz 22.6 T-4a-ii slice-3b (Codex 019ebc7e) — the broker's DURABLE control-plane audit sink: the adapter
 * that turns each broker {@link AuditEvent} into a durably-committed, hash-chained, session-keyed
 * {@link SessionRecorder} entry. This is the sink {@link RemoteBridgeBroker}'s
 * record-BEFORE-permit rule needs (ADR-0034 §6: a recorder failure BLOCKS permit issuance) — the
 * best-effort inbound log sink ({@code remoteBridgeInboundAuditSink}) does NOT gate authority, this one does.
 *
 * <p><b>Fail-closed contract:</b> {@link SessionRecorder#record} returns {@code false} when the entry did NOT
 * durably land (chain-append broke OR the underlying {@code RecordingSink} write did not commit). The adapter
 * turns that {@code false} into a thrown {@link IllegalStateException}, because {@link RemoteBridgeAuditSink}
 * MUST throw on a durable-write failure so the broker issues no permit. A null/blank event also throws.
 *
 * <p><b>Session-keyed:</b> one {@link SessionRecorder} (one recording chain, {@code chainId == sessionId}) per
 * session, lazily created via the injected {@code recorderFactory}. The factory is what binds the real
 * {@code DbRecordingSink(sessionId, jdbc, schema)} + the recording-anchor signer — wired at the broker bean
 * (slice-3c), so this adapter stays unit-testable against a fake {@code RecordingSink} + a real recorder.
 *
 * <p><b>Kind mapping:</b> a broker decision is a {@link RecordKind#POLICY_EVENT}; a duress/kill is a
 * {@link RecordKind#KILL}. Unknown event types map to {@code POLICY_EVENT} (a broker audit event is, by
 * construction, a policy-plane event) — never silently dropped.
 */
public final class DurableRemoteBridgeAuditSink implements RemoteBridgeAuditSink {

    private final Function<String, SessionRecorder> recorderFactory;
    private final ConcurrentMap<String, SessionRecorder> recorders = new ConcurrentHashMap<>();

    /**
     * @param recorderFactory sessionId → the durable {@link SessionRecorder} for that session's chain. MUST
     *                        be non-null and MUST NOT return null (a null recorder would be fail-OPEN).
     */
    public DurableRemoteBridgeAuditSink(Function<String, SessionRecorder> recorderFactory) {
        this.recorderFactory = Objects.requireNonNull(recorderFactory, "recorderFactory");
    }

    @Override
    public void record(AuditEvent event) {
        if (event == null || event.sessionId() == null || event.sessionId().isBlank()) {
            // fail-closed: a broker that cannot identify the session to record against must not get a permit
            throw new IllegalStateException("remote-bridge durable audit requires a non-blank session id");
        }
        recordKind(event.sessionId(), mapKind(event.eventType()), event.contentHash(), event.epochMillis());
    }

    public void recordAgentOutput(String sessionId, String contentHash, long epochMillis) {
        recordKind(sessionId, RecordKind.AGENT_OUTPUT, contentHash, epochMillis);
    }

    /** The broker's audit event-type prefix for a duress/kill record (everything else is a policy event). */
    static final String KILL_EVENT_PREFIX = "KILL";

    private static RecordKind mapKind(String eventType) {
        // PREFIX-match, NOT substring (Codex slice-3b note): the broker records its decisions as
        // "ALLOW_DECISION:<operationId>" — an operationId that merely CONTAINS "kill" (e.g.
        // "ALLOW_DECISION:kill-pod") must NOT be misclassified as a KILL record. A duress/kill is recorded
        // with the "KILL" prefix; anything else is a policy-plane event.
        if (eventType != null && eventType.startsWith(KILL_EVENT_PREFIX)) {
            return RecordKind.KILL;
        }
        return RecordKind.POLICY_EVENT;
    }

    private void recordKind(String sessionId, RecordKind kind, String contentHash, long epochMillis) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalStateException("remote-bridge durable audit requires a non-blank session id");
        }
        SessionRecorder recorder = recorders.computeIfAbsent(sessionId, recorderFactory);
        if (recorder == null) {
            throw new IllegalStateException(
                    "remote-bridge durable audit recorder factory returned null for session=" + sessionId);
        }
        boolean committed = recorder.record(kind, contentHash, epochMillis);
        if (!committed) {
            throw new IllegalStateException(
                    "remote-bridge durable audit write did not commit for session=" + sessionId);
        }
    }
}

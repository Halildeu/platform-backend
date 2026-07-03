package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.DuressResponsePolicy.DuressSignal;
import com.example.endpointadmin.remoteaccess.bridge.contract.WireContract;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session-scoped duress signal source. Missing, stale, malformed, or explicitly ambiguous state classifies as
 * AMBIGUOUS, so the broker keeps killing until a real, fresh signal has been recorded for this session.
 */
public final class SessionDuressSignalStore implements TrustEvidenceAssembler.DuressSignalSource {

    public record SignalRecord(String sessionId,
                               String operatorSubject,
                               DuressSignal signal,
                               long recordedAtEpochMillis,
                               long expiresAtEpochMillis) {
    }

    private final Map<String, SignalRecord> bySessionId = new ConcurrentHashMap<>();
    private final long ttlMillis;

    public SessionDuressSignalStore(long ttlMillis) {
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("ttlMillis must be positive");
        }
        this.ttlMillis = ttlMillis;
    }

    public Optional<SignalRecord> record(String sessionId, String operatorSubject, DuressSignal signal,
                                         long nowEpochMillis) {
        if (!WireContract.isValidId(sessionId) || !WireContract.isValidId(operatorSubject)
                || signal == null || signal == DuressSignal.AMBIGUOUS || nowEpochMillis < 0) {
            return Optional.empty();
        }
        SignalRecord record = new SignalRecord(sessionId, operatorSubject, signal, nowEpochMillis,
                nowEpochMillis + ttlMillis);
        bySessionId.put(sessionId, record);
        return Optional.of(record);
    }

    public Optional<SignalRecord> current(String sessionId, long nowEpochMillis) {
        if (!WireContract.isValidId(sessionId) || nowEpochMillis < 0) {
            return Optional.empty();
        }
        SignalRecord record = bySessionId.get(sessionId);
        if (record == null) {
            return Optional.empty();
        }
        if (nowEpochMillis >= record.expiresAtEpochMillis()) {
            bySessionId.remove(sessionId, record);
            return Optional.empty();
        }
        return Optional.of(record);
    }

    public void evict(String sessionId) {
        if (sessionId != null) {
            bySessionId.remove(sessionId);
        }
    }

    @Override
    public DuressSignal classify(String sessionId, long nowEpochMillis) {
        return current(sessionId, nowEpochMillis)
                .map(SignalRecord::signal)
                .orElse(DuressSignal.AMBIGUOUS);
    }
}

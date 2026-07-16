package com.example.audiogateway.service;

import com.example.audiogateway.config.AudioGatewayProperties;
import com.example.audiogateway.dto.ChunkAdmissionResponse;
import com.example.audiogateway.service.AudioChunkDispatcher.ChunkDispatchCommand;
import com.example.audiogateway.service.AudioChunkDispatcher.DispatchOutcome;
import com.example.audiogateway.service.AudioSessionRegistry.ChunkOutcome;
import com.example.audiogateway.service.AudioSessionRegistry.ChunkRecordCommand;
import com.example.audiogateway.service.AudioSessionRegistry.CreateOutcome;
import com.example.audiogateway.service.AudioSessionRegistry.ExpiryOutcome;
import com.example.audiogateway.service.AudioSessionRegistry.FinishOutcome;
import com.example.audiogateway.service.AudioSessionRegistry.SessionCreateCommand;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Service;

/**
 * Bounded in-memory implementation of {@link AudioSessionRegistry}.
 *
 * <p>PR-gw-01A + PR-gw-01B-core + PR-gw-01B3 scope (Codex {@code 019e8c26} +
 * {@code 019e8d78} + {@code 019e8df2} iter-2 AGREE): no Redis, no persistence;
 * restart loses all sessions. Durability sonraki slice'da Redis Streams (PR-gw-01C).
 *
 * <p><b>Concurrency contract:</b> {@code create}, {@code admitChunk} (with dispatcher),
 * {@code finish} are fully {@code synchronized} on the same monitor — atomic state
 * transitions guaranteed. Dispatcher.dispatch() called inside synchronized region —
 * B3 NoOp/PoC OK; Redis (C) async pattern refactor borç notu.
 */
@Service
public class InMemoryAudioSessionRegistry implements AudioSessionRegistry {

    private final AudioGatewayProperties props;
    private final ConcurrentMap<String, SessionRecord> sessions = new ConcurrentHashMap<>();
    private final Map<String, IdempotencyEntry> startReplay;

    public InMemoryAudioSessionRegistry(final AudioGatewayProperties props) {
        this.props = props;
        final int cap = Math.max(1, props.getIdempotency().getReplayCacheSize());
        this.startReplay = Collections.synchronizedMap(
                new LinkedHashMap<String, IdempotencyEntry>(cap, 0.75f, false) {
                    @Override
                    protected boolean removeEldestEntry(final Map.Entry<String, IdempotencyEntry> eldest) {
                        return size() > cap;
                    }
                });
    }

    @Override
    public synchronized CreateOutcome create(final SessionCreateCommand cmd) {
        final String idempKey = startReplayKey(cmd.tenantId(), cmd.userId(), cmd.idempotencyKey());
        final String signature = startSignature(cmd);

        final IdempotencyEntry existing = startReplay.get(idempKey);
        if (existing != null) {
            if (!Objects.equals(existing.signature(), signature)) {
                return new CreateOutcome.IdempotencyConflict(existing.sessionId());
            }
            final SessionRecord rec = sessions.get(existing.sessionId());
            if (rec != null) {
                return new CreateOutcome.Replayed(rec);
            }
            startReplay.remove(idempKey);
        }

        if (activeCountLocked() >= props.getBounds().getMaxActiveSessions()) {
            return new CreateOutcome.RegistryFull(props.getBounds().getMaxActiveSessions());
        }

        final String sessionId = "SES-" + UUID.randomUUID();
        final SessionRecord rec = new SessionRecord(
                sessionId, cmd.tenantId(), cmd.userId(), cmd.meetingId(), cmd.deviceId(),
                cmd.language(), cmd.audioFormat(), cmd.sampleRateHz(), cmd.channels(),
                cmd.idempotencyKey(), cmd.sessionStartMs(),
                SessionState.STARTED,
                -1L, 0L, 0L, null, null,
                null, 0L, cmd.sessionStartMs());

        sessions.put(sessionId, rec);
        startReplay.put(idempKey, new IdempotencyEntry(sessionId, signature));

        return new CreateOutcome.Created(rec);
    }

    @Override
    public Optional<SessionRecord> get(final String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    /**
     * Atomic admit-chunk + dispatch (Codex {@code 019e8df2} iter-2 AGREE B3 atomicity gate).
     * Synchronized monitor; dispatcher called inside but only AFTER replay/conflict
     * checks pass. State mutation ONLY after dispatcher Accepted.
     */
    @Override
    public synchronized ChunkOutcome admitChunk(final ChunkRecordCommand cmd,
                                                 final AudioChunkDispatcher dispatcher) {
        final SessionRecord existing = sessions.get(cmd.sessionId());
        if (existing == null) {
            return new ChunkOutcome.NotFound();
        }
        if (!Objects.equals(existing.tenantId(), cmd.tenantId())
                || !Objects.equals(existing.userId(), cmd.userId())) {
            return new ChunkOutcome.OwnerMismatch();
        }

        final ExpiryOutcome expiry = expireIfDueLocked(
                cmd.sessionId(), cmd.nowMs(), maxSessionAgeMs(), cmd.correlationId(), dispatcher);
        if (expiry instanceof ExpiryOutcome.Expired) {
            return new ChunkOutcome.SessionExpired(false);
        }
        if (expiry instanceof ExpiryOutcome.CleanupFailed) {
            return new ChunkOutcome.SessionExpired(true);
        }
        if (existing.state() != SessionState.STARTED && existing.state() != SessionState.STREAMING) {
            return new ChunkOutcome.InvalidState(existing.state());
        }

        // Replay path: same chunkSeq as last accepted + same key + same payload SHA-256.
        // Dispatcher NOT called again (Codex iter-2: cache set only after dispatch-accepted).
        if (cmd.chunkSeq() == existing.lastAcceptedChunkSeq()) {
            if (Objects.equals(cmd.idempotencyKey(), existing.lastChunkIdempotencyKey())
                    && Objects.equals(cmd.payload().sha256(), existing.lastChunkPayloadSha256())) {
                final ChunkAdmissionResponse resp = new ChunkAdmissionResponse(
                        existing.sessionId(), null,
                        existing.lastAcceptedChunkSeq(), existing.chunkCount(),
                        existing.lastChunkAtMs(), true);
                return new ChunkOutcome.Replayed(resp);
            }
            return new ChunkOutcome.IdempotencyConflict();
        }

        // Strict contiguous (init -1 → first chunk 0; sonraki last+1)
        final long expected = existing.lastAcceptedChunkSeq() + 1L;
        if (cmd.chunkSeq() != expected) {
            return new ChunkOutcome.OutOfOrder(expected, cmd.chunkSeq());
        }

        // Atomicity gate (Codex `019e8df2` iter-1 P1 absorb): dispatcher called AFTER all
        // admission validations pass; state mutated ONLY on Accepted. QueueFull/Unavailable
        // → NO mutation; client retry path falls through to fresh attempt next time.
        final ChunkDispatchCommand dispatchCmd = new ChunkDispatchCommand(
                cmd.sessionId(), existing.tenantId(), existing.userId(),
                existing.meetingId(), existing.deviceId(), existing.language(),
                existing.audioFormat(), existing.sampleRateHz(), existing.channels(),
                cmd.chunkSeq(), cmd.chunkStartedAtMs(), cmd.correlationId(),
                cmd.payload());

        final DispatchOutcome dispatchOutcome = dispatcher.dispatch(dispatchCmd);
        return switch (dispatchOutcome) {
            case DispatchOutcome.Accepted a -> {
                final SessionRecord updated = existing.withAcceptedChunk(
                        cmd.chunkSeq(), cmd.nowMs(), cmd.idempotencyKey(), cmd.payload().sha256());
                sessions.put(cmd.sessionId(), updated);
                final ChunkAdmissionResponse resp = new ChunkAdmissionResponse(
                        updated.sessionId(), null,
                        updated.lastAcceptedChunkSeq(), updated.chunkCount(),
                        updated.lastChunkAtMs(), false);
                yield new ChunkOutcome.Accepted(updated, resp);
            }
            case DispatchOutcome.QueueFull q -> new ChunkOutcome.DispatchQueueFull(q.retryAfterSeconds());
            case DispatchOutcome.Unavailable u -> new ChunkOutcome.DispatchUnavailable(u.retryAfterSeconds());
        };
    }

    @Override
    public synchronized FinishOutcome finish(
            final String sessionId,
            final String finishIdempotencyKey,
            final Long tenantId,
            final Long userId,
            final long nowMs,
            final String correlationId,
            final AudioChunkDispatcher dispatcher) {
        final SessionRecord existing = sessions.get(sessionId);
        if (existing == null) {
            return new FinishOutcome.NotFound();
        }
        if (!Objects.equals(existing.tenantId(), tenantId)
                || !Objects.equals(existing.userId(), userId)) {
            return new FinishOutcome.OwnerMismatch();
        }
        if (existing.state() == SessionState.FINISHED) {
            if (Objects.equals(existing.finishIdempotencyKey(), finishIdempotencyKey)) {
                return new FinishOutcome.AlreadyFinished(existing);
            }
            return new FinishOutcome.IdempotencyConflict(existing.finishIdempotencyKey());
        }

        final ExpiryOutcome expiry = expireIfDueLocked(
                sessionId, nowMs, maxSessionAgeMs(), correlationId, dispatcher);
        if (expiry instanceof ExpiryOutcome.Expired) {
            return new FinishOutcome.SessionExpired(false);
        }
        if (expiry instanceof ExpiryOutcome.CleanupFailed) {
            return new FinishOutcome.SessionExpired(true);
        }

        final SessionRecord finished = existing.withFinish(finishIdempotencyKey, nowMs);
        if (!sessions.replace(sessionId, existing, finished)) {
            final SessionRecord current = sessions.get(sessionId);
            if (current != null && current.state() == SessionState.FINISHED
                    && Objects.equals(current.finishIdempotencyKey(), finishIdempotencyKey)) {
                return new FinishOutcome.AlreadyFinished(current);
            }
            return new FinishOutcome.IdempotencyConflict(
                    current != null ? current.finishIdempotencyKey() : null);
        }
        return new FinishOutcome.Finished(finished);
    }

    @Override
    public synchronized ExpiryOutcome expireIfDue(
            final String sessionId,
            final long nowMs,
            final long maxSessionAgeMs,
            final String correlationId,
            final AudioChunkDispatcher dispatcher) {
        return expireIfDueLocked(
                sessionId, nowMs, maxSessionAgeMs, correlationId, dispatcher);
    }

    @Override
    public List<ExpiryOutcome> expireDue(
            final long nowMs,
            final long maxSessionAgeMs,
            final String correlationId,
            final AudioChunkDispatcher dispatcher) {
        final List<String> candidates = sessions.values().stream()
                .filter(InMemoryAudioSessionRegistry::isActive)
                .filter(record -> isDue(record, nowMs, maxSessionAgeMs))
                .map(SessionRecord::sessionId)
                .toList();
        return candidates.stream()
                .map(sessionId -> expireIfDue(
                        sessionId, nowMs, maxSessionAgeMs, correlationId, dispatcher))
                .toList();
    }

    @Override
    public synchronized int activeCount() {
        return activeCountLocked();
    }

    @Override
    public int capacity() {
        return props.getBounds().getMaxActiveSessions();
    }

    // ----- internals --------------------------------------------------------

    private static String startReplayKey(final Long tenantId, final Long userId, final String key) {
        return tenantId + "|" + userId + "|POST|/sessions|" + key;
    }

    private static String startSignature(final SessionCreateCommand cmd) {
        return cmd.meetingId() + "|" + cmd.deviceId() + "|" + cmd.language() + "|"
                + cmd.audioFormat() + "|" + cmd.sampleRateHz() + "|" + cmd.channels();
    }

    private ExpiryOutcome expireIfDueLocked(
            final String sessionId,
            final long nowMs,
            final long maxSessionAgeMs,
            final String correlationId,
            final AudioChunkDispatcher dispatcher) {
        final SessionRecord existing = sessions.get(sessionId);
        if (existing == null) {
            return new ExpiryOutcome.NotFound();
        }
        if (!isActive(existing) || !isDue(existing, nowMs, maxSessionAgeMs)) {
            return new ExpiryOutcome.NotExpired(existing);
        }

        try {
            dispatcher.discardSession(new AudioChunkDispatcher.SessionDiscardCommand(
                    existing.sessionId(), existing.tenantId(), existing.userId(), correlationId));
        } catch (final RuntimeException ex) {
            return new ExpiryOutcome.CleanupFailed(existing, ex.getClass().getSimpleName());
        }

        sessions.remove(sessionId);
        return new ExpiryOutcome.Expired(existing);
    }

    private int activeCountLocked() {
        return sessions.size();
    }

    private static boolean isActive(final SessionRecord record) {
        return record.state() == SessionState.STARTED || record.state() == SessionState.STREAMING;
    }

    private static boolean isDue(
            final SessionRecord record, final long nowMs, final long maxSessionAgeMs) {
        return maxSessionAgeMs >= 0L
                && nowMs >= record.sessionStartMs()
                && nowMs - record.sessionStartMs() >= maxSessionAgeMs;
    }

    private long maxSessionAgeMs() {
        return props.getBounds().getMaxSessionMinutes() * 60_000L;
    }

    private record IdempotencyEntry(String sessionId, String signature) {
    }
}

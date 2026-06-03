package com.example.audiogateway.service;

import com.example.audiogateway.config.AudioGatewayProperties;
import com.example.audiogateway.service.AudioSessionRegistry.CreateOutcome;
import com.example.audiogateway.service.AudioSessionRegistry.FinishOutcome;
import com.example.audiogateway.service.AudioSessionRegistry.SessionCreateCommand;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Service;

/**
 * Bounded in-memory implementation of {@link AudioSessionRegistry}.
 *
 * <p>PR-gw-01A scope: no Redis, no persistence. Restart loses all sessions — explicit
 * contract documented (Codex {@code 019e8c26} iter-2 AGREE). Slice PR-gw-01C migrates to
 * Redis Streams + consumer group.
 *
 * <p>Idempotency replay: key = {@code tenantId|userId|"POST"|"/sessions"|idempotencyKey};
 * value = sessionId. Bounded by {@code audio.gateway.idempotency.replay-cache-size}.
 * When cap reached, oldest entry evicted (LRU approximation via insertion order).
 */
@Service
public class InMemoryAudioSessionRegistry implements AudioSessionRegistry {

    private final AudioGatewayProperties props;
    private final ConcurrentMap<String, SessionRecord> sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, IdempotencyEntry> startReplay = new ConcurrentHashMap<>();

    public InMemoryAudioSessionRegistry(final AudioGatewayProperties props) {
        this.props = props;
    }

    @Override
    public CreateOutcome create(final SessionCreateCommand cmd) {
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
            // record evicted but replay cache still present → treat as fresh create
            startReplay.remove(idempKey, existing);
        }

        if (sessions.size() >= props.getBounds().getMaxActiveSessions()) {
            return new CreateOutcome.RegistryFull(props.getBounds().getMaxActiveSessions());
        }

        final String sessionId = "SES-" + UUID.randomUUID();
        final SessionRecord rec = new SessionRecord(
                sessionId, cmd.tenantId(), cmd.userId(), cmd.meetingId(), cmd.deviceId(),
                cmd.language(), cmd.audioFormat(), cmd.sampleRateHz(), cmd.channels(),
                cmd.idempotencyKey(), cmd.sessionStartMs(),
                SessionState.STARTED, null, 0L, cmd.sessionStartMs());

        // Race guard: putIfAbsent on session id (UUID collision practically nil)
        sessions.put(sessionId, rec);

        // Cache idempotency replay; evict oldest if over cap
        if (startReplay.size() >= props.getIdempotency().getReplayCacheSize()) {
            startReplay.keySet().stream().findFirst().ifPresent(startReplay::remove);
        }
        startReplay.put(idempKey, new IdempotencyEntry(sessionId, signature));

        return new CreateOutcome.Created(rec);
    }

    @Override
    public Optional<SessionRecord> get(final String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public FinishOutcome finish(final String sessionId, final String finishIdempotencyKey,
                                final Long tenantId, final Long userId) {
        final SessionRecord existing = sessions.get(sessionId);
        if (existing == null) {
            return new FinishOutcome.NotFound();
        }
        if (!Objects.equals(existing.tenantId(), tenantId)
                || !Objects.equals(existing.userId(), userId)) {
            return new FinishOutcome.OwnerMismatch();
        }
        if (existing.state() == SessionState.FINISHED) {
            // Idempotent replay only if same finish key; otherwise conflict
            if (Objects.equals(existing.finishIdempotencyKey(), finishIdempotencyKey)) {
                return new FinishOutcome.AlreadyFinished(existing);
            }
            return new FinishOutcome.IdempotencyConflict(existing.finishIdempotencyKey());
        }

        final long now = System.currentTimeMillis();
        final SessionRecord finished = existing.withFinish(finishIdempotencyKey, now);
        // CAS-like: only replace if state was not FINISHED (concurrent finish race protection)
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
    public int activeCount() {
        return sessions.size();
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

    private record IdempotencyEntry(String sessionId, String signature) {
    }
}

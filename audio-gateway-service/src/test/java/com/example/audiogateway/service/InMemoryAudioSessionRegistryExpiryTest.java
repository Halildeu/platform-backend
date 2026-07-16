package com.example.audiogateway.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.audiogateway.config.AudioGatewayProperties;
import com.example.audiogateway.dto.AudioChunkPayload;
import com.example.audiogateway.dto.AudioFormat;
import com.example.audiogateway.service.AudioChunkDispatcher.SessionDiscardCommand;
import com.example.audiogateway.service.AudioSessionRegistry.ChunkOutcome;
import com.example.audiogateway.service.AudioSessionRegistry.ChunkRecordCommand;
import com.example.audiogateway.service.AudioSessionRegistry.CreateOutcome;
import com.example.audiogateway.service.AudioSessionRegistry.ExpiryOutcome;
import com.example.audiogateway.service.AudioSessionRegistry.FinishOutcome;
import com.example.audiogateway.service.AudioSessionRegistry.SessionCreateCommand;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class InMemoryAudioSessionRegistryExpiryTest {

    private static final long START_MS = 1_000L;
    private static final long MAX_AGE_MS = 60_000L;

    @Test
    void expiredSessionIsCleanedBeforeRemovalAndCapacityReopens() {
        final AudioGatewayProperties props = propsWithCapacity(1);
        final InMemoryAudioSessionRegistry registry = new InMemoryAudioSessionRegistry(props);
        final SessionRecord first = created(registry, "start-idempotency-key-001", START_MS);
        assertThat(registry.create(createCommand("start-idempotency-key-002", START_MS)))
                .isInstanceOf(CreateOutcome.RegistryFull.class);
        final AtomicInteger discards = new AtomicInteger();
        final AudioChunkDispatcher dispatcher = countingDiscarder(discards);

        final ExpiryOutcome outcome = registry.expireIfDue(
                first.sessionId(), START_MS + MAX_AGE_MS, MAX_AGE_MS,
                "expiry-correlation", dispatcher);

        assertThat(outcome).isInstanceOf(ExpiryOutcome.Expired.class);
        assertThat(discards).hasValue(1);
        assertThat(registry.get(first.sessionId())).isEmpty();
        assertThat(registry.activeCount()).isZero();
        assertThat(registry.create(createCommand("start-idempotency-key-003", START_MS + MAX_AGE_MS)))
                .isInstanceOf(CreateOutcome.Created.class);
    }

    @Test
    void cleanupFailureRetainsSessionAndNextAttemptRetries() {
        final InMemoryAudioSessionRegistry registry =
                new InMemoryAudioSessionRegistry(propsWithCapacity(1));
        final SessionRecord record = created(registry, "retry-idempotency-key-001", START_MS);
        final AtomicInteger attempts = new AtomicInteger();
        final AudioChunkDispatcher dispatcher = new AudioChunkDispatcher() {
            @Override
            public DispatchOutcome dispatch(final ChunkDispatchCommand cmd) {
                return new DispatchOutcome.Accepted();
            }

            @Override
            public void discardSession(final SessionDiscardCommand cmd) {
                if (attempts.incrementAndGet() == 1) {
                    throw new IllegalStateException("fixture cleanup failure");
                }
            }
        };

        final ExpiryOutcome first = registry.expireIfDue(
                record.sessionId(), START_MS + MAX_AGE_MS, MAX_AGE_MS,
                "expiry-correlation-1", dispatcher);

        assertThat(first).isInstanceOfSatisfying(ExpiryOutcome.CleanupFailed.class,
                failed -> assertThat(failed.errorType()).isEqualTo("IllegalStateException"));
        assertThat(registry.get(record.sessionId())).isPresent();
        assertThat(registry.activeCount()).isEqualTo(1);

        final ExpiryOutcome retry = registry.expireIfDue(
                record.sessionId(), START_MS + MAX_AGE_MS + 1L, MAX_AGE_MS,
                "expiry-correlation-2", dispatcher);

        assertThat(retry).isInstanceOf(ExpiryOutcome.Expired.class);
        assertThat(attempts).hasValue(2);
        assertThat(registry.get(record.sessionId())).isEmpty();
    }

    @Test
    void sweepExpiresAbandonedSessionsWithoutAFollowUpRequest() {
        final InMemoryAudioSessionRegistry registry =
                new InMemoryAudioSessionRegistry(propsWithCapacity(2));
        created(registry, "sweep-idempotency-key-001", START_MS);
        created(registry, "sweep-idempotency-key-002", START_MS + MAX_AGE_MS + 1L);
        final AtomicInteger discards = new AtomicInteger();

        final var outcomes = registry.expireDue(
                START_MS + MAX_AGE_MS, MAX_AGE_MS,
                "sweep-correlation", countingDiscarder(discards));

        assertThat(outcomes).singleElement().isInstanceOf(ExpiryOutcome.Expired.class);
        assertThat(discards).hasValue(1);
        assertThat(registry.activeCount()).isEqualTo(1);
    }

    @Test
    void chunkAdmissionAtomicallyExpiresBeforeDispatch() {
        final InMemoryAudioSessionRegistry registry =
                new InMemoryAudioSessionRegistry(propsWithCapacity(1));
        final SessionRecord record = created(registry, "chunk-expiry-idempotency-001", START_MS);
        final AtomicInteger dispatches = new AtomicInteger();
        final AtomicInteger discards = new AtomicInteger();
        final AudioChunkDispatcher dispatcher = new AudioChunkDispatcher() {
            @Override
            public DispatchOutcome dispatch(final ChunkDispatchCommand cmd) {
                dispatches.incrementAndGet();
                return new DispatchOutcome.Accepted();
            }

            @Override
            public void discardSession(final SessionDiscardCommand cmd) {
                discards.incrementAndGet();
            }
        };

        final ChunkOutcome outcome = registry.admitChunk(new ChunkRecordCommand(
                record.sessionId(), 1L, 2L, "chunk-idempotency-key-001", 0L,
                START_MS, "chunk-expiry-correlation",
                AudioChunkPayload.of(new byte[] {1, 2}, "fixture-hash"),
                START_MS + MAX_AGE_MS), dispatcher);

        assertThat(outcome).isEqualTo(new ChunkOutcome.SessionExpired(false));
        assertThat(dispatches).hasValue(0);
        assertThat(discards).hasValue(1);
        assertThat(registry.get(record.sessionId())).isEmpty();
    }

    @Test
    void finishedRecordsRemainInsideTheExistingRegistryCapacityBound() {
        final InMemoryAudioSessionRegistry registry =
                new InMemoryAudioSessionRegistry(propsWithCapacity(1));
        final SessionRecord record = created(registry, "finish-capacity-idempotency-001", START_MS);
        final AtomicInteger discards = new AtomicInteger();

        final FinishOutcome outcome = registry.finish(
                record.sessionId(), "finish-capacity-key-001", 1L, 2L,
                START_MS + 1L, "finish-capacity-correlation", countingDiscarder(discards));

        assertThat(outcome).isInstanceOf(FinishOutcome.Finished.class);
        assertThat(discards).hasValue(0);
        assertThat(registry.activeCount()).isEqualTo(1);
        assertThat(registry.create(createCommand(
                "finish-capacity-idempotency-002", START_MS + 1L)))
                .isInstanceOf(CreateOutcome.RegistryFull.class);
    }

    private static AudioGatewayProperties propsWithCapacity(final int capacity) {
        final AudioGatewayProperties props = new AudioGatewayProperties();
        props.getBounds().setMaxActiveSessions(capacity);
        props.getBounds().setMaxSessionMinutes(1);
        return props;
    }

    private static SessionRecord created(
            final InMemoryAudioSessionRegistry registry,
            final String idempotencyKey,
            final long startMs) {
        return ((CreateOutcome.Created) registry.create(createCommand(idempotencyKey, startMs))).record();
    }

    private static SessionCreateCommand createCommand(
            final String idempotencyKey, final long startMs) {
        return new SessionCreateCommand(
                1L, 2L, "22222222-2222-4222-8222-222222222222", "desktop-1", "tr",
                AudioFormat.PCM16, 16_000, 1, idempotencyKey, startMs);
    }

    private static AudioChunkDispatcher countingDiscarder(final AtomicInteger discards) {
        return new AudioChunkDispatcher() {
            @Override
            public DispatchOutcome dispatch(final ChunkDispatchCommand cmd) {
                return new DispatchOutcome.Accepted();
            }

            @Override
            public void discardSession(final SessionDiscardCommand cmd) {
                discards.incrementAndGet();
            }
        };
    }
}

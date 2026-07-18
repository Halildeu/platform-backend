package com.example.audiogateway.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.audiogateway.config.AudioGatewayProperties;
import com.example.audiogateway.dto.AudioChunkPayload;
import com.example.audiogateway.dto.AudioFormat;
import com.example.audiogateway.service.AudioChunkDispatcher.DispatchOutcome;
import com.example.audiogateway.service.AudioSessionRegistry.ChunkOutcome;
import com.example.audiogateway.service.AudioSessionRegistry.ChunkRecordCommand;
import com.example.audiogateway.service.AudioSessionRegistry.CreateOutcome;
import com.example.audiogateway.service.AudioSessionRegistry.ExpiryOutcome;
import com.example.audiogateway.service.AudioSessionRegistry.FinishOutcome;
import com.example.audiogateway.service.AudioSessionRegistry.LiveFrameCommand;
import com.example.audiogateway.service.AudioSessionRegistry.LiveFrameOutcome;
import com.example.audiogateway.service.AudioSessionRegistry.SessionCreateCommand;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class InMemoryAudioSessionRegistryLiveSequenceTest {

    @Test
    void webSocketOnlyReconnectRetainsLiveSequenceAndRejectsUncoveredGap() {
        final InMemoryAudioSessionRegistry registry = registry();
        final SessionRecord session = create(registry);
        final AtomicInteger commits = new AtomicInteger();

        assertThat(admit(registry, session, 0L, commits))
                .isInstanceOf(LiveFrameOutcome.Accepted.class);

        // A new WebSocket connection uses the same session-owned sequence state.
        assertThat(admit(registry, session, 0L, commits))
                .isEqualTo(new LiveFrameOutcome.Duplicate(0L));
        assertThat(admit(registry, session, 2L, commits))
                .isEqualTo(new LiveFrameOutcome.Gap(1L, 2L));
        assertThat(admit(registry, session, 1L, commits))
                .isInstanceOf(LiveFrameOutcome.Accepted.class);

        assertThat(commits).hasValue(2);
        assertThat(registry.get(session.sessionId()).orElseThrow().lastAcceptedChunkSeq())
                .as("WebSocket relay must not mutate the durable REST baseline")
                .isEqualTo(-1L);
    }

    @Test
    void restAcceptedSequenceRelaysOnceAndDurableBaselineAllowsReconnectJump() {
        final InMemoryAudioSessionRegistry registry = registry();
        final SessionRecord session = create(registry);
        final AtomicInteger commits = new AtomicInteger();

        assertThat(admitRest(registry, session, 0L))
                .isInstanceOf(ChunkOutcome.Accepted.class);
        assertThat(admit(registry, session, 0L, commits))
                .isInstanceOf(LiveFrameOutcome.Accepted.class);
        assertThat(admit(registry, session, 0L, commits))
                .isEqualTo(new LiveFrameOutcome.Duplicate(0L));

        assertThat(admitRest(registry, session, 1L))
                .isInstanceOf(ChunkOutcome.Accepted.class);
        assertThat(admitRest(registry, session, 2L))
                .isInstanceOf(ChunkOutcome.Accepted.class);
        assertThat(admit(registry, session, 2L, commits))
                .isInstanceOf(LiveFrameOutcome.Accepted.class);
        assertThat(admit(registry, session, 2L, commits))
                .isEqualTo(new LiveFrameOutcome.Duplicate(2L));

        assertThat(admit(registry, session, 4L, commits))
                .isEqualTo(new LiveFrameOutcome.Gap(3L, 4L));
        assertThat(admit(registry, session, 3L, commits))
                .isInstanceOf(LiveFrameOutcome.Accepted.class);

        assertThat(commits).hasValue(3);
        final SessionRecord current = registry.get(session.sessionId()).orElseThrow();
        assertThat(current.lastAcceptedChunkSeq()).isEqualTo(2L);
        assertThat(current.chunkCount()).isEqualTo(3L);
    }

    @Test
    void failedMandatoryCommitDoesNotAdvanceSequence() {
        final InMemoryAudioSessionRegistry registry = registry();
        final SessionRecord session = create(registry);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> registry.admitLiveFrame(
                        command(session, 0L), current -> {
                            throw new IllegalStateException("audit unavailable");
                        }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(registry.get(session.sessionId()).orElseThrow().lastAcceptedChunkSeq())
                .isEqualTo(-1L);
        assertThat(registry.admitLiveFrame(command(session, 0L), current -> { }))
                .isEqualTo(new LiveFrameOutcome.Accepted(0L));
        assertThat(registry.admitLiveFrame(command(session, 0L), current -> { }))
                .isEqualTo(new LiveFrameOutcome.Duplicate(0L));
        assertThat(registry.get(session.sessionId()).orElseThrow().lastAcceptedChunkSeq())
                .isEqualTo(-1L);
    }

    @Test
    void terminalLifecycleRejectsFinishedSequenceAndRemovesExpiredSequence() {
        final InMemoryAudioSessionRegistry registry = registry();
        final AudioChunkDispatcher dispatcher = command -> new DispatchOutcome.Accepted();
        final SessionRecord finishedSession = create(registry);

        assertThat(admit(registry, finishedSession, 0L, new AtomicInteger()))
                .isInstanceOf(LiveFrameOutcome.Accepted.class);
        assertThat(registry.finish(
                        finishedSession.sessionId(), "finish-idempotency-key-0001",
                        finishedSession.tenantId(), finishedSession.userId(), 2_100L,
                        "correlation-finish", dispatcher))
                .isInstanceOf(FinishOutcome.Finished.class);
        assertThat(admit(registry, finishedSession, 1L, new AtomicInteger()))
                .isEqualTo(new LiveFrameOutcome.InvalidState(SessionState.FINISHED));

        final SessionRecord expiredSession = create(registry, "0002");
        assertThat(admit(registry, expiredSession, 0L, new AtomicInteger()))
                .isInstanceOf(LiveFrameOutcome.Accepted.class);
        assertThat(registry.expireIfDue(
                        expiredSession.sessionId(), 2_001L, 1_000L,
                        "correlation-expiry", dispatcher))
                .isInstanceOf(ExpiryOutcome.Expired.class);
        assertThat(admit(registry, expiredSession, 1L, new AtomicInteger()))
                .isInstanceOf(LiveFrameOutcome.NotFound.class);
    }

    private static LiveFrameOutcome admit(
            final InMemoryAudioSessionRegistry registry,
            final SessionRecord session,
            final long sequence,
            final AtomicInteger commits) {
        return registry.admitLiveFrame(command(session, sequence), current -> commits.incrementAndGet());
    }

    private static ChunkOutcome admitRest(
            final InMemoryAudioSessionRegistry registry,
            final SessionRecord session,
            final long sequence) {
        return registry.admitChunk(new ChunkRecordCommand(
                session.sessionId(), session.tenantId(), session.userId(),
                "rest-idempotency-key-" + sequence, sequence,
                2_000L + sequence, "correlation-rest",
                AudioChunkPayload.of(
                        new byte[]{(byte) sequence},
                        "sha256:fixture-" + sequence),
                2_001L + sequence),
                command -> new DispatchOutcome.Accepted());
    }

    private static LiveFrameCommand command(final SessionRecord session, final long sequence) {
        return new LiveFrameCommand(
                session.sessionId(), session.tenantId(), session.userId(), sequence, 2_000L + sequence);
    }

    private static InMemoryAudioSessionRegistry registry() {
        final AudioGatewayProperties properties = new AudioGatewayProperties();
        properties.getBounds().setMaxActiveSessions(4);
        return new InMemoryAudioSessionRegistry(properties);
    }

    private static SessionRecord create(final InMemoryAudioSessionRegistry registry) {
        return create(registry, "0001");
    }

    private static SessionRecord create(
            final InMemoryAudioSessionRegistry registry,
            final String idempotencySuffix) {
        return ((CreateOutcome.Created) registry.create(new SessionCreateCommand(
                1L, 2L, "meeting-1", "desktop-1", "tr",
                AudioFormat.PCM16, 16_000, 1,
                "start-idempotency-key-" + idempotencySuffix, 1_000L))).record();
    }
}

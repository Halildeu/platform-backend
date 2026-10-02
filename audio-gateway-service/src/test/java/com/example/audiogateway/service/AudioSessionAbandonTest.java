package com.example.audiogateway.service;

import com.example.audiogateway.config.AudioGatewayProperties;
import com.example.audiogateway.dto.AudioFormat;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AudioSessionAbandonTest {
    final InMemoryAudioSessionRegistry registry = new InMemoryAudioSessionRegistry(new AudioGatewayProperties());
    final AudioChunkDispatcher dispatcher = mock(AudioChunkDispatcher.class);
    final String id = ((AudioSessionRegistry.CreateOutcome.Created) registry.create(
            new AudioSessionRegistry.SessionCreateCommand(1L, 2L, "meeting", "phone", "tr", AudioFormat.PCM16,
                    16000, 1, "start-idempotency-0001", 1000))).record().sessionId();

    private AudioSessionRegistry.AbandonOutcome abandon() {
        return registry.abandon(id, "abandon-idempotency-0001", 1L, 2L, 2000, "correlation", dispatcher);
    }
    @Test void abandonsWithoutFlushingAndExactRetryIsIdempotent() {
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        registry.abandonment(id).subscribe(null, null, () -> cancelled.set(true));
        assertThat(abandon()).isInstanceOf(AudioSessionRegistry.AbandonOutcome.Abandoned.class);
        assertThat(cancelled).isTrue();
        assertThat(abandon()).isInstanceOfSatisfying(AudioSessionRegistry.AbandonOutcome.Abandoned.class,
                result -> assertThat(result.replayed()).isTrue());
        verify(dispatcher).discardSession(any());
        verify(dispatcher, never()).finishSession(any());
        assertThat(registry.finish(id, "finish-idempotency-0001", 1L, 2L, 2001, "corr", dispatcher))
                .isInstanceOf(AudioSessionRegistry.FinishOutcome.InvalidState.class);
        assertThat(registry.admitLiveFrame(new AudioSessionRegistry.LiveFrameCommand(id, 1L, 2L, 0, 2002), rec -> {}))
                .isInstanceOf(AudioSessionRegistry.LiveFrameOutcome.InvalidState.class);
    }
    @Test void cleanupFailureBlocksAdmissionButDoesNotLoseRetry() {
        doThrow(new IllegalStateException("fixture")).doNothing().when(dispatcher).discardSession(any());
        assertThat(abandon()).isInstanceOf(AudioSessionRegistry.AbandonOutcome.CleanupFailed.class);
        assertThat(registry.get(id).orElseThrow().state()).isEqualTo(SessionState.ABANDONING);
        assertThat(registry.finish(id, "finish-idempotency-0001", 1L, 2L, 2001, "corr", dispatcher))
                .isInstanceOf(AudioSessionRegistry.FinishOutcome.InvalidState.class);
        assertThat(abandon()).isInstanceOf(AudioSessionRegistry.AbandonOutcome.Abandoned.class);
        verify(dispatcher, times(2)).discardSession(any());
    }
    @Test void ownerMismatchHasNoCleanupOrStateChange() {
        assertThat(registry.abandon(id, "abandon-idempotency-0001", 1L, 3L, 2000, "corr", dispatcher))
                .isInstanceOf(AudioSessionRegistry.AbandonOutcome.OwnerMismatch.class);
        assertThat(registry.get(id).orElseThrow().state()).isEqualTo(SessionState.STARTED);
        verifyNoInteractions(dispatcher);
    }
    @Test void finishedSessionAndDifferentAbandonKeyConflict() {
        abandon();
        assertThat(registry.abandon(id, "another-idempotency-0001", 1L, 2L, 2000, "corr", dispatcher))
                .isInstanceOf(AudioSessionRegistry.AbandonOutcome.Conflict.class);
    }
    @Test void previouslyFinishedSessionIsNeverRewritten() {
        registry.finish(id, "finish-idempotency-0001", 1L, 2L, 2000, "corr", dispatcher);
        assertThat(abandon()).isInstanceOf(AudioSessionRegistry.AbandonOutcome.Conflict.class);
        assertThat(registry.get(id).orElseThrow().state()).isEqualTo(SessionState.FINISHED);
        verify(dispatcher, never()).discardSession(any());
    }
}

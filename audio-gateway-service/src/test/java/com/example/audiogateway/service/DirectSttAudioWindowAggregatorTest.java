package com.example.audiogateway.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.audiogateway.dto.AudioChunkPayload;
import com.example.audiogateway.dto.AudioFormat;
import com.example.audiogateway.service.AudioChunkDispatcher.ChunkDispatchCommand;
import com.example.audiogateway.service.AudioChunkDispatcher.SessionFinishCommand;

import org.junit.jupiter.api.Test;

class DirectSttAudioWindowAggregatorTest {

    @Test
    void splitsContinuousPcmIntoOrderedFixedWindowsAndFlushesTail() {
        final DirectSttAudioWindowAggregator aggregator =
                new DirectSttAudioWindowAggregator(5, 4);

        final var first = aggregator.append(command("SES-1", 0L, pcm16Seconds(7)), pcm16Seconds(7));
        assertThat(first.capacityExceeded()).isFalse();
        assertThat(first.windows()).singleElement().satisfies(window -> {
            assertThat(window.windowSeq()).isZero();
            assertThat(window.firstChunkSeq()).isZero();
            assertThat(window.lastChunkSeq()).isZero();
            assertThat(window.durationMs()).isEqualTo(5_000);
            assertThat(window.audio()).hasSize(160_000);
        });
        assertThat(aggregator.bufferedBytes()).isEqualTo(64_000);

        final var second = aggregator.append(command("SES-1", 1L, pcm16Seconds(3)), pcm16Seconds(3));
        assertThat(second.windows()).singleElement().satisfies(window -> {
            assertThat(window.windowSeq()).isEqualTo(1L);
            assertThat(window.firstChunkSeq()).isZero();
            assertThat(window.lastChunkSeq()).isEqualTo(1L);
            assertThat(window.durationMs()).isEqualTo(5_000);
        });

        assertThat(aggregator.finish(new SessionFinishCommand("SES-1", 1L, 2L, "finish")))
                .isEmpty();
        assertThat(aggregator.activeSessions()).isZero();
        assertThat(aggregator.bufferedBytes()).isZero();
    }

    @Test
    void rejectsAdditionalSessionWhenMemorySessionBoundIsReached() {
        final DirectSttAudioWindowAggregator aggregator =
                new DirectSttAudioWindowAggregator(10, 1);

        assertThat(aggregator.append(command("SES-1", 0L, pcm16Seconds(1)), pcm16Seconds(1))
                .capacityExceeded()).isFalse();
        assertThat(aggregator.append(command("SES-2", 0L, pcm16Seconds(1)), pcm16Seconds(1))
                .capacityExceeded()).isTrue();
        assertThat(aggregator.activeSessions()).isEqualTo(1);
    }

    @Test
    void wrongOwnerCannotFlushAnotherSessionsAudio() {
        final DirectSttAudioWindowAggregator aggregator =
                new DirectSttAudioWindowAggregator(10, 2);
        aggregator.append(command("SES-1", 0L, pcm16Seconds(1)), pcm16Seconds(1));

        assertThat(aggregator.finish(new SessionFinishCommand("SES-1", 99L, 2L, "finish")))
                .isEmpty();
        assertThat(aggregator.activeSessions()).isEqualTo(1);
        assertThat(aggregator.finish(new SessionFinishCommand("SES-1", 1L, 2L, "finish")))
                .isPresent();
    }

    private static ChunkDispatchCommand command(
            final String sessionId,
            final long chunkSeq,
            final byte[] audio) {
        return new ChunkDispatchCommand(
                sessionId,
                1L,
                2L,
                "22222222-2222-4222-8222-222222222222",
                "desktop",
                "tr",
                AudioFormat.PCM16,
                16_000,
                1,
                chunkSeq,
                1_000L + chunkSeq * 100L,
                "corr-" + chunkSeq,
                AudioChunkPayload.of(audio, "hash-" + chunkSeq));
    }

    private static byte[] pcm16Seconds(final int seconds) {
        return new byte[16_000 * 2 * seconds];
    }
}

package com.example.audiogateway.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.audiogateway.config.AudioGatewayProperties;
import com.example.audiogateway.dto.AudioFormat;
import com.example.audiogateway.service.AudioChunkDispatcher.SessionDiscardCommand;
import com.example.audiogateway.service.AudioSessionRegistry.CreateOutcome;
import com.example.audiogateway.service.AudioSessionRegistry.SessionCreateCommand;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class AudioSessionExpiryCoordinatorTest {

    @Test
    void scheduledSweepCleansAnAbandonedSessionWithoutAnotherRequest() {
        final AudioGatewayProperties props = new AudioGatewayProperties();
        props.getBounds().setMaxSessionMinutes(0);
        final InMemoryAudioSessionRegistry registry = new InMemoryAudioSessionRegistry(props);
        final SessionRecord record = ((CreateOutcome.Created) registry.create(
                new SessionCreateCommand(
                        1L, 2L, "22222222-2222-4222-8222-222222222222", "desktop-1", "tr",
                        AudioFormat.PCM16, 16_000, 1,
                        "coordinator-idempotency-key-001", 1L))).record();
        final AtomicInteger discards = new AtomicInteger();
        final AudioChunkDispatcher dispatcher = new AudioChunkDispatcher() {
            @Override
            public DispatchOutcome dispatch(final ChunkDispatchCommand cmd) {
                return new DispatchOutcome.Accepted();
            }

            @Override
            public void discardSession(final SessionDiscardCommand cmd) {
                discards.incrementAndGet();
            }
        };
        final SimpleMeterRegistry meters = new SimpleMeterRegistry();
        final AudioSessionExpiryCoordinator coordinator =
                new AudioSessionExpiryCoordinator(props, registry, dispatcher, meters);

        coordinator.sweepExpiredSessions();

        assertThat(registry.get(record.sessionId())).isEmpty();
        assertThat(discards).hasValue(1);
        assertThat(meters.get("audio_gateway_session_expired_total").counter().count())
                .isEqualTo(1.0);
    }
}

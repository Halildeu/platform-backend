package com.example.audiogateway.service;

import com.example.audiogateway.config.AudioGatewayProperties;
import com.example.audiogateway.service.AudioSessionRegistry.ExpiryOutcome;

import io.micrometer.core.instrument.MeterRegistry;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Coordinates request-triggered and proactive max-session expiry cleanup. */
@Service
public class AudioSessionExpiryCoordinator {

    private static final Logger log = LoggerFactory.getLogger(AudioSessionExpiryCoordinator.class);
    private static final String SWEEP_CORRELATION_ID = "session-expiry-sweep";

    private final AudioGatewayProperties props;
    private final AudioSessionRegistry registry;
    private final AudioChunkDispatcher dispatcher;
    private final MeterRegistry meters;

    public AudioSessionExpiryCoordinator(
            final AudioGatewayProperties props,
            final AudioSessionRegistry registry,
            final AudioChunkDispatcher dispatcher,
            final MeterRegistry meters) {
        this.props = props;
        this.registry = registry;
        this.dispatcher = dispatcher;
        this.meters = meters;
        meters.counter("audio_gateway_session_expired_total");
        meters.counter("audio_gateway_session_expiry_cleanup_error_total");
    }

    public ExpiryOutcome expireIfDue(
            final String sessionId, final long nowMs, final String correlationId) {
        final ExpiryOutcome outcome = registry.expireIfDue(
                sessionId, nowMs, maxSessionAgeMs(), correlationId, dispatcher);
        record(outcome);
        return outcome;
    }

    @Scheduled(
            fixedDelayString = "${audio.gateway.bounds.session-expiry-sweep-ms:30000}",
            initialDelayString = "${audio.gateway.bounds.session-expiry-sweep-ms:30000}")
    void sweepExpiredSessions() {
        final List<ExpiryOutcome> outcomes = registry.expireDue(
                Instant.now().toEpochMilli(), maxSessionAgeMs(),
                SWEEP_CORRELATION_ID, dispatcher);
        outcomes.forEach(this::record);
    }

    private long maxSessionAgeMs() {
        return props.getBounds().getMaxSessionMinutes() * 60_000L;
    }

    private void record(final ExpiryOutcome outcome) {
        switch (outcome) {
            case ExpiryOutcome.Expired expired -> {
                meters.counter("audio_gateway_session_expired_total").increment();
                log.info("Audio session expired and terminal state discarded sessionId={}",
                        expired.record().sessionId());
            }
            case ExpiryOutcome.CleanupFailed failed -> {
                meters.counter("audio_gateway_session_expiry_cleanup_error_total").increment();
                log.warn("Audio session expiry cleanup failed; retained for retry sessionId={} err={}",
                        failed.record().sessionId(), failed.errorType());
            }
            case ExpiryOutcome.NotExpired ignored -> {
                // Normal request path.
            }
            case ExpiryOutcome.NotFound ignored -> {
                // A concurrent sweep already removed it.
            }
        }
    }
}

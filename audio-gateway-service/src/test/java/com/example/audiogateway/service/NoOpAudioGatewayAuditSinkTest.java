package com.example.audiogateway.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NoOpAudioGatewayAuditSinkTest {

    private final NoOpAudioGatewayAuditSink sink = new NoOpAudioGatewayAuditSink();

    @Test
    void consentGrantAndRevocationFailClosedWhenDurableTransportIsDisabled() {
        AudioGatewayAuditSink.AuditEvent.RecordingConsentGranted granted =
                new AudioGatewayAuditSink.AuditEvent.RecordingConsentGranted(
                        "11111111-1111-1111-1111-111111111111",
                        "22222222-2222-2222-2222-222222222222",
                        42L,
                        7L,
                        "33333333-3333-3333-3333-333333333333",
                        "44444444-4444-4444-4444-444444444444",
                        "user:7",
                        "v1",
                        "sha256:" + "a".repeat(64),
                        "tr-TR",
                        "corr-grant",
                        1_700_001_000_000L);
        AudioGatewayAuditSink.AuditEvent.RecordingConsentRevoked revoked =
                new AudioGatewayAuditSink.AuditEvent.RecordingConsentRevoked(
                        "11111111-1111-1111-1111-111111111111",
                        "22222222-2222-2222-2222-222222222222",
                        42L,
                        7L,
                        "33333333-3333-3333-3333-333333333333",
                        "44444444-4444-4444-4444-444444444444",
                        "user:7",
                        "v1",
                        2L,
                        "USER_WITHDREW",
                        "corr-revoke",
                        1_700_002_000_000L);

        assertThatThrownBy(() -> sink.emit(granted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Durable consent audit transport is disabled");
        assertThatThrownBy(() -> sink.emit(revoked))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Durable consent audit transport is disabled");
    }

    @Test
    void nonAuthoritativeTelemetryRemainsNoOp() {
        AudioGatewayAuditSink.AuditEvent.ChunkAdmissionRejected telemetry =
                new AudioGatewayAuditSink.AuditEvent.ChunkAdmissionRejected(
                        "session-1", 42L, 7L, 1L, 429, "QUEUE_FULL",
                        1L, "corr-telemetry", 1_700_003_000_000L);

        assertThatCode(() -> sink.emit(telemetry)).doesNotThrowAnyException();
    }
}

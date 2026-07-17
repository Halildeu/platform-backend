package com.example.audiogateway.service;

import org.springframework.stereotype.Service;

/**
 * No-op audit sink for non-authoritative telemetry events.
 *
 * <p>Codex {@code 019e8df2} iter-2 AGREE: default bean for PR-gw-01B3 PoC.
 * Real persistence (KVKK Madde 12 7yr immutable retention) ayrı audit PR scope.
 * Tests override via {@code @TestConfiguration @Primary recordingAudioGatewayAuditSink}.
 *
 * <p>Consent grant/revocation is an authoritative legal/audit command and must
 * never look accepted when durable audit transport is disabled. Those variants
 * therefore fail closed; their controllers map this exception to retryable 503.
 */
@Service
public class NoOpAudioGatewayAuditSink implements AudioGatewayAuditSink {

    @Override
    public void emit(final AuditEvent event) {
        if (event instanceof AuditEvent.RecordingConsentGranted
                || event instanceof AuditEvent.RecordingConsentRevoked) {
            throw new IllegalStateException("Durable consent audit transport is disabled");
        }
        // intentionally no-op
    }
}

package com.example.audiogateway.service;

/**
 * Audio Gateway audit sink — per-event emission point.
 *
 * <p>Codex {@code 019e8df2} iter-2 AGREE PR-gw-01B3 scope started with
 * {@link AuditEvent.ChunkAdmissionRejected}. Faz 24 recorder consent adds
 * {@link AuditEvent.RecordingConsentGranted} as the server-time legal proof
 * before audio capture begins. Faz 24 #188 adds
 * {@link AuditEvent.ChunkForwardedToComputePlane} as the lawful-basis proof
 * before direct-STT sends raw audio to the compute plane.
 *
 * <p><b>Emission contract:</b> response yazılmadan ÖNCE sync emit. Chunk
 * rejection uses controller {@code safeEmit(...)} because audit loss must not
 * corrupt the primary rejection response. Consent proof is stricter: controller
 * fail-closes when the sink throws.
 *
 * <p><b>PII boundary (ADR-0030 + Codex iter-2):</b> Audit event Idempotency-Key payload'a
 * YAZILMASIN (PII transitif sızıntı). No bearer token, auth-code, raw email,
 * raw consent text, audio bytes, or transcript text.
 */
public interface AudioGatewayAuditSink {

    /** Emit a single audit event. Thread-safety: implementation responsibility. */
    void emit(AuditEvent event);

    /**
     * Audit event types.
     */
    sealed interface AuditEvent {

        /**
         * Chunk admission rejection — emitted for B3 admission boundary rejections.
         *
         * <p><b>Emit boundary (Codex {@code 019e8df2} iter-3 P1.3 absorb):</b>
         * 400 invalid headers / format mismatch / body-read-error +
         * 404 session not found +
         * 413 OVERSIZE (declared or actual or bounded-read-limit) +
         * 415 FORMAT_REJECTED +
         * 409 INVALID_TRANSITION / OUT_OF_ORDER / IDEMPOTENCY_CONFLICT +
         * 429 QUEUE_FULL (dispatcher) +
         * 503 STT_UNAVAILABLE (dispatcher).
         *
         * <p><b>Authz failures (401 AUTH_INVALID, 403 owner mismatch / MEETING_FORBIDDEN)
         * B3 DIŞI</b> — auth audit ayrı security/audit boundary (separate PR).
         *
         * @param retryAfterSeconds {@code null} for non-retryable rejections (400/404/413/415/409);
         *                          positive long for 429/503 dispatcher retry hints
         */
        record ChunkAdmissionRejected(
                String sessionId,
                Long tenantId,
                Long userId,
                long chunkSeq,
                int httpStatus,
                String rejectionCode,
                Long retryAfterSeconds,
                String correlationId,
                long timestampMs
        ) implements AuditEvent {
        }

        /**
         * Recording consent proof — emitted before recorder audio capture starts.
         *
         * @param consentTextHash immutable hash of the exact consent text shown to
         *                        the user; never the raw text itself
         * @param acceptedAtMs server-side acceptance timestamp
         */
        record RecordingConsentGranted(
                String meetingId,
                String captureId,
                Long tenantId,
                Long userId,
                String subjectId,
                String consentVersion,
                String consentTextHash,
                String locale,
                String correlationId,
                long acceptedAtMs
        ) implements AuditEvent {
        }

        /**
         * Compute-plane raw-audio transit proof — emitted after a chunk is admitted and
         * immediately before direct-STT builds/sends the live-stt HTTP request.
         *
         * <p><b>PII boundary:</b> carries only routing and integrity metadata. No raw
         * audio bytes, transcript text/segments, bearer token, idempotency key, or
         * destination URL.
         */
        record ChunkForwardedToComputePlane(
                String sessionId,
                Long tenantId,
                Long userId,
                String meetingId,
                String deviceId,
                String language,
                long chunkSeq,
                long windowSeq,
                long firstChunkSeq,
                long lastChunkSeq,
                long windowStartedAtMs,
                long windowEndedAtMs,
                int audioDurationMs,
                String flushReason,
                String audioFormat,
                int sampleRateHz,
                int channels,
                String sha256,
                int byteLength,
                String correlationId,
                long forwardedAtMs,
                String computePlane
        ) implements AuditEvent {
        }
    }
}

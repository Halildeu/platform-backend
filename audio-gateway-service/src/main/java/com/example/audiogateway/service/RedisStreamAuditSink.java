package com.example.audiogateway.service;

import com.example.audiogateway.config.AudioGatewayProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Redis Streams audit sink — Faz 24 KVKK audit pipeline producer (gitops#1249).
 *
 * <p>Persists every {@link AudioGatewayAuditSink.AuditEvent} emitted at the
 * chunk-admission boundary onto the {@code audit:events} Redis stream via
 * {@code XADD}. The downstream {@code audit-event-consumer-service} reads the
 * stream with a consumer group, persists each event to an immutable,
 * hash-chained {@code audit_event} table (KVKK m.12 audit-archive 7yr trail).
 *
 * <p><b>Config-gated, DEFAULT-OFF (mirrors dispatcher {@code mode} discipline):</b>
 * active only when {@code audio.gateway.audit.redis.enabled=true}. Otherwise the
 * {@link NoOpAudioGatewayAuditSink} stays the bean — live behaviour is unchanged
 * until an overlay flips the flag. {@link Primary} so this wins injection over
 * the NoOp bean when both are present.
 *
 * <p><b>Reuses the audio-gateway Redis connection</b> ({@link StringRedisTemplate},
 * the same auto-configured {@code spring.data.redis.*} connection the
 * {@link RedisStreamsAudioChunkDispatcher} uses for {@code audio:chunks:pNN}) —
 * no second connection pool/config.
 *
 * <p><b>safeEmit contract preserved:</b> the controller already wraps every
 * {@code emit(...)} in {@code try { sink.emit(event); } catch (Exception) {}}
 * ({@code AudioSessionController.safeAudit}). This sink therefore lets
 * {@link org.springframework.dao.DataAccessException} propagate; it is the
 * controller's swallow that keeps audit-sink failure from corrupting the
 * primary response. We additionally guard the failure path with a PII-safe
 * WARN log so a silent audit drop is at least observable.
 *
 * <p><b>PII boundary (ADR-0030 + AudioGatewayAuditSink javadoc):</b> only
 * non-PII recorder/audit identifiers are written. No Idempotency-Key, no bearer
 * token/auth-code, no raw consent text, no audio bytes, no transcript.
 *
 * <p><b>Field mapping is null-safe:</b> {@code tenantId}/{@code userId}/
 * {@code retryAfterSeconds} are nullable {@link Long}s — absent values are
 * mapped to the empty string so the consumer reads them back as "null/absent"
 * (Redis stream field values are strings).
 */
@Service
@Primary
@ConditionalOnProperty(name = "audio.gateway.audit.redis.enabled", havingValue = "true")
public class RedisStreamAuditSink implements AudioGatewayAuditSink {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamAuditSink.class);

    /** Canonical audit event type discriminator for {@code ChunkAdmissionRejected}. */
    static final String EVENT_TYPE_CHUNK_ADMISSION_REJECTED = "CHUNK_ADMISSION_REJECTED";
    /** Canonical audit event type discriminator for recorder consent proof. */
    static final String EVENT_TYPE_RECORDING_CONSENT_GRANTED = "RECORDING_CONSENT_GRANTED";
    /** Canonical audit event type discriminator for recorder consent withdrawal. */
    static final String EVENT_TYPE_RECORDING_CONSENT_REVOKED = "RECORDING_CONSENT_REVOKED";
    /** Canonical audit event type discriminator for direct-STT compute-plane transit. */
    static final String EVENT_TYPE_CHUNK_FORWARDED_TO_COMPUTE_PLANE =
            "CHUNK_FORWARDED_TO_COMPUTE_PLANE";
    /** Canonical audit event type discriminator for transcript access. */
    static final String EVENT_TYPE_TRANSCRIPT_EVENTS_ACCESSED = "TRANSCRIPT_EVENTS_ACCESSED";

    private final StringRedisTemplate redis;
    private final AudioGatewayProperties.Audit.Redis cfg;

    public RedisStreamAuditSink(final StringRedisTemplate redis,
                                final AudioGatewayProperties properties) {
        this.redis = redis;
        this.cfg = properties.getAudit().getRedis();
    }

    @Override
    public void emit(final AuditEvent event) {
        if (event instanceof AuditEvent.ChunkAdmissionRejected rejected) {
            emitChunkAdmissionRejected(rejected);
            return;
        }
        if (event instanceof AuditEvent.RecordingConsentGranted consent) {
            emitRecordingConsentGranted(consent);
            return;
        }
        if (event instanceof AuditEvent.RecordingConsentRevoked revoked) {
            emitRecordingConsentRevoked(revoked);
            return;
        }
        if (event instanceof AuditEvent.ChunkForwardedToComputePlane forwarded) {
            emitChunkForwardedToComputePlane(forwarded);
            return;
        }
        if (event instanceof AuditEvent.TranscriptEventsAccessed accessed) {
            emitTranscriptEventsAccessed(accessed);
            return;
        }
        // Future AuditEvent variants (SessionLifecycle / ChunkForwarded ...) are
        // not current scope; ignore unknown types rather than mis-map them.
        log.debug("Ignoring unmapped audit event type {}", event.getClass().getSimpleName());
    }

    private void emitChunkAdmissionRejected(final AuditEvent.ChunkAdmissionRejected e) {
        final Map<String, String> fields = new LinkedHashMap<>();
        fields.put("eventType", EVENT_TYPE_CHUNK_ADMISSION_REJECTED);
        fields.put("sessionId", nullSafe(e.sessionId()));
        fields.put("tenantId", longOrEmpty(e.tenantId()));
        fields.put("userId", longOrEmpty(e.userId()));
        fields.put("chunkSeq", Long.toString(e.chunkSeq()));
        fields.put("httpStatus", Integer.toString(e.httpStatus()));
        fields.put("rejectionCode", nullSafe(e.rejectionCode()));
        fields.put("retryAfterSeconds", longOrEmpty(e.retryAfterSeconds()));
        fields.put("correlationId", nullSafe(e.correlationId()));
        fields.put("timestampMs", Long.toString(e.timestampMs()));

        try {
            final MapRecord<String, String, String> record =
                    StreamRecords.mapBacked(fields).withStreamKey(cfg.getStreamKey());
            redis.opsForStream().add(record);
        } catch (final DataAccessException ex) {
            // PII-safe failure log (ids + class only). Re-throw so the
            // controller's safeAudit swallow is the single isolation point —
            // we do NOT silently absorb here (that would hide audit loss from
            // the swallow's own logging contract).
            log.warn("ALERT audit XADD failed; event may be lost err={} sessionId={} chunkSeq={} status={}",
                    ex.getClass().getSimpleName(), e.sessionId(), e.chunkSeq(), e.httpStatus());
            throw ex;
        }
    }

    private void emitRecordingConsentGranted(final AuditEvent.RecordingConsentGranted e) {
        final Map<String, String> fields = new LinkedHashMap<>();
        fields.put("eventType", EVENT_TYPE_RECORDING_CONSENT_GRANTED);
        fields.put("meetingId", nullSafe(e.meetingId()));
        fields.put("captureId", nullSafe(e.captureId()));
        fields.put("tenantId", longOrEmpty(e.tenantId()));
        fields.put("userId", longOrEmpty(e.userId()));
        fields.put("canonicalTenantId", nullSafe(e.canonicalTenantId()));
        fields.put("orgId", nullSafe(e.orgId()));
        fields.put("subjectId", nullSafe(e.subjectId()));
        fields.put("consentVersion", nullSafe(e.consentVersion()));
        fields.put("consentTextHash", nullSafe(e.consentTextHash()));
        fields.put("locale", nullSafe(e.locale()));
        fields.put("correlationId", nullSafe(e.correlationId()));
        fields.put("acceptedAtMs", Long.toString(e.acceptedAtMs()));
        fields.put("timestampMs", Long.toString(e.acceptedAtMs()));

        try {
            final MapRecord<String, String, String> record =
                    StreamRecords.mapBacked(fields).withStreamKey(cfg.getStreamKey());
            // Consent evidence is authoritative until the durable consumer has
            // persisted it. A generic stream cap must never evict an unconsumed
            // grant, even when maxLen is configured for operational events.
            redis.opsForStream().add(record);
        } catch (final DataAccessException ex) {
            log.warn("ALERT consent audit XADD failed; event may be lost err={} meetingId={} captureId={}",
                    ex.getClass().getSimpleName(), e.meetingId(), e.captureId());
            throw ex;
        }
    }

    private void emitRecordingConsentRevoked(final AuditEvent.RecordingConsentRevoked e) {
        final Map<String, String> fields = new LinkedHashMap<>();
        fields.put("eventType", EVENT_TYPE_RECORDING_CONSENT_REVOKED);
        fields.put("meetingId", nullSafe(e.meetingId()));
        fields.put("captureId", nullSafe(e.captureId()));
        fields.put("tenantId", longOrEmpty(e.tenantId()));
        fields.put("userId", longOrEmpty(e.userId()));
        fields.put("canonicalTenantId", nullSafe(e.canonicalTenantId()));
        fields.put("orgId", nullSafe(e.orgId()));
        fields.put("subjectId", nullSafe(e.subjectId()));
        fields.put("consentVersion", nullSafe(e.consentVersion()));
        fields.put("consentRevision", Long.toString(e.consentRevision()));
        fields.put("reasonCode", nullSafe(e.reasonCode()));
        fields.put("correlationId", nullSafe(e.correlationId()));
        fields.put("revokedAtMs", Long.toString(e.revokedAtMs()));
        fields.put("timestampMs", Long.toString(e.revokedAtMs()));

        try {
            final MapRecord<String, String, String> record =
                    StreamRecords.mapBacked(fields).withStreamKey(cfg.getStreamKey());
            // Revocation is part of the same authoritative consent history as
            // the grant and therefore cannot use producer-side trimming.
            redis.opsForStream().add(record);
        } catch (final DataAccessException ex) {
            log.warn("ALERT consent revoke audit XADD failed; command denied err={} meetingId={} captureId={}",
                    ex.getClass().getSimpleName(), e.meetingId(), e.captureId());
            throw ex;
        }
    }

    private void emitChunkForwardedToComputePlane(final AuditEvent.ChunkForwardedToComputePlane e) {
        final Map<String, String> fields = new LinkedHashMap<>();
        fields.put("eventType", EVENT_TYPE_CHUNK_FORWARDED_TO_COMPUTE_PLANE);
        fields.put("sessionId", nullSafe(e.sessionId()));
        fields.put("tenantId", longOrEmpty(e.tenantId()));
        fields.put("userId", longOrEmpty(e.userId()));
        fields.put("meetingId", nullSafe(e.meetingId()));
        fields.put("deviceId", nullSafe(e.deviceId()));
        fields.put("language", nullSafe(e.language()));
        fields.put("chunkSeq", Long.toString(e.chunkSeq()));
        fields.put("windowSeq", Long.toString(e.windowSeq()));
        fields.put("firstChunkSeq", Long.toString(e.firstChunkSeq()));
        fields.put("lastChunkSeq", Long.toString(e.lastChunkSeq()));
        fields.put("windowStartedAtMs", Long.toString(e.windowStartedAtMs()));
        fields.put("windowEndedAtMs", Long.toString(e.windowEndedAtMs()));
        fields.put("audioDurationMs", Integer.toString(e.audioDurationMs()));
        fields.put("flushReason", nullSafe(e.flushReason()));
        fields.put("audioFormat", nullSafe(e.audioFormat()));
        fields.put("sampleRateHz", Integer.toString(e.sampleRateHz()));
        fields.put("channels", Integer.toString(e.channels()));
        fields.put("sha256", nullSafe(e.sha256()));
        fields.put("byteLength", Integer.toString(e.byteLength()));
        fields.put("correlationId", nullSafe(e.correlationId()));
        fields.put("forwardedAtMs", Long.toString(e.forwardedAtMs()));
        fields.put("computePlane", nullSafe(e.computePlane()));

        try {
            final MapRecord<String, String, String> record =
                    StreamRecords.mapBacked(fields).withStreamKey(cfg.getStreamKey());
            redis.opsForStream().add(record);
        } catch (final DataAccessException ex) {
            log.warn("ALERT compute-plane audit XADD failed; raw audio forward blocked err={} sessionId={} chunkSeq={}",
                    ex.getClass().getSimpleName(), e.sessionId(), e.chunkSeq());
            throw ex;
        }
    }

    private void emitTranscriptEventsAccessed(final AuditEvent.TranscriptEventsAccessed e) {
        final Map<String, String> fields = new LinkedHashMap<>();
        fields.put("eventType", EVENT_TYPE_TRANSCRIPT_EVENTS_ACCESSED);
        fields.put("sessionId", nullSafe(e.sessionId()));
        fields.put("tenantId", longOrEmpty(e.tenantId()));
        fields.put("userId", longOrEmpty(e.userId()));
        fields.put("meetingId", nullSafe(e.meetingId()));
        fields.put("deliveryMode", nullSafe(e.deliveryMode()));
        fields.put("afterCursor", nullSafe(e.afterCursor()));
        fields.put("requestedLimit", Integer.toString(e.requestedLimit()));
        fields.put("correlationId", nullSafe(e.correlationId()));
        fields.put("accessedAtMs", Long.toString(e.accessedAtMs()));

        try {
            final MapRecord<String, String, String> record =
                    StreamRecords.mapBacked(fields).withStreamKey(cfg.getStreamKey());
            redis.opsForStream().add(record);
        } catch (final DataAccessException ex) {
            log.warn("ALERT transcript access audit XADD failed; event may be lost err={} sessionId={} mode={}",
                    ex.getClass().getSimpleName(), e.sessionId(), e.deliveryMode());
            throw ex;
        }
    }

    private static String nullSafe(final String value) {
        return value == null ? "" : value;
    }

    private static String longOrEmpty(final Long value) {
        return value == null ? "" : Long.toString(value);
    }
}

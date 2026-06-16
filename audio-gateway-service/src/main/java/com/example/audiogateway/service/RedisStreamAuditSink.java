package com.example.audiogateway.service;

import com.example.audiogateway.config.AudioGatewayProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
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
 * active only when {@code audiogateway.audit.redis.enabled=true}. Otherwise the
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
 * <p><b>PII boundary (ADR-0030 + AudioGatewayAuditSink javadoc):</b> only the
 * non-PII admission fields are written — sessionId, tenantId, userId, chunkSeq,
 * httpStatus, rejectionCode, retryAfterSeconds, correlationId, timestampMs. No
 * Idempotency-Key, no audio bytes, no transcript.
 *
 * <p><b>Field mapping is null-safe:</b> {@code tenantId}/{@code userId}/
 * {@code retryAfterSeconds} are nullable {@link Long}s — absent values are
 * mapped to the empty string so the consumer reads them back as "null/absent"
 * (Redis stream field values are strings).
 */
@Service
@Primary
@ConditionalOnProperty(name = "audiogateway.audit.redis.enabled", havingValue = "true")
public class RedisStreamAuditSink implements AudioGatewayAuditSink {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamAuditSink.class);

    /** Canonical audit event type discriminator for {@code ChunkAdmissionRejected}. */
    static final String EVENT_TYPE_CHUNK_ADMISSION_REJECTED = "CHUNK_ADMISSION_REJECTED";

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
        // Future AuditEvent variants (SessionLifecycle / ChunkForwarded ...) are
        // not B3 scope; ignore unknown types rather than mis-map them.
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
            if (cfg.getMaxLen() > 0) {
                redis.opsForStream().add(record,
                        XAddOptions.maxlen(cfg.getMaxLen()).approximateTrimming(true));
            } else {
                redis.opsForStream().add(record);
            }
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

    private static String nullSafe(final String value) {
        return value == null ? "" : value;
    }

    private static String longOrEmpty(final Long value) {
        return value == null ? "" : Long.toString(value);
    }
}

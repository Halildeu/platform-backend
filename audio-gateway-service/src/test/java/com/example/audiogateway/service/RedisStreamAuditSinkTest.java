package com.example.audiogateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.audiogateway.config.AudioGatewayProperties;
import com.example.audiogateway.service.AudioGatewayAuditSink.AuditEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Faz 24 KVKK audit pipeline (gitops#1249) — {@link RedisStreamAuditSink} unit
 * tests. The producer-side XADD field mapping + null-safety + safeEmit
 * propagation are verified here; the full producer→stream→consumer→immutable
 * persist round-trip is proven by the consumer service's Testcontainers
 * end-to-end test (audit-event-consumer-service).
 */
class RedisStreamAuditSinkTest {

    private StreamOperations<String, Object, Object> streamOps;
    private RedisStreamAuditSink sink;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        final StringRedisTemplate redis = mock(StringRedisTemplate.class);
        streamOps = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(streamOps);

        final AudioGatewayProperties props = new AudioGatewayProperties();
        props.getAudit().getRedis().setEnabled(true);
        props.getAudit().getRedis().setStreamKey("audit:events");
        props.getAudit().getRedis().setMaxLen(0);

        sink = new RedisStreamAuditSink(redis, props);
    }

    private static AuditEvent.ChunkAdmissionRejected rejected() {
        return new AuditEvent.ChunkAdmissionRejected(
                "sess-9", 42L, 7L, 3L, 429, "QUEUE_FULL", 10L, "corr-9", 1_000L);
    }

    @SuppressWarnings("unchecked")
    @Test
    void emitMapsAllFieldsToStreamRecord() {
        when(streamOps.add(any(MapRecord.class))).thenReturn(RecordId.of("1-0"));

        sink.emit(rejected());

        final ArgumentCaptor<MapRecord<String, String, String>> captor =
                ArgumentCaptor.forClass(MapRecord.class);
        verify(streamOps).add(captor.capture());
        final MapRecord<String, String, String> record = captor.getValue();
        assertThat(record.getStream()).isEqualTo("audit:events");
        assertThat(record.getValue())
                .containsEntry("eventType", "CHUNK_ADMISSION_REJECTED")
                .containsEntry("sessionId", "sess-9")
                .containsEntry("tenantId", "42")
                .containsEntry("userId", "7")
                .containsEntry("chunkSeq", "3")
                .containsEntry("httpStatus", "429")
                .containsEntry("rejectionCode", "QUEUE_FULL")
                .containsEntry("retryAfterSeconds", "10")
                .containsEntry("correlationId", "corr-9")
                .containsEntry("timestampMs", "1000");
    }

    @SuppressWarnings("unchecked")
    @Test
    void emitIsNullSafeForNullableLongs() {
        // tenantId / userId / retryAfterSeconds null (non-retryable 404 etc.) →
        // mapped to empty string, never a literal "null" or an NPE.
        when(streamOps.add(any(MapRecord.class))).thenReturn(RecordId.of("1-0"));

        sink.emit(new AuditEvent.ChunkAdmissionRejected(
                "sess-x", null, null, 0L, 404, "SESSION_NOT_FOUND", null, "corr-x", 2_000L));

        final ArgumentCaptor<MapRecord<String, String, String>> captor =
                ArgumentCaptor.forClass(MapRecord.class);
        verify(streamOps).add(captor.capture());
        final MapRecord<String, String, String> record = captor.getValue();
        assertThat(record.getValue())
                .containsEntry("tenantId", "")
                .containsEntry("userId", "")
                .containsEntry("retryAfterSeconds", "")
                .containsEntry("rejectionCode", "SESSION_NOT_FOUND");
    }

    @SuppressWarnings("unchecked")
    @Test
    void emitPiiBoundaryNoAudioNoIdempotencyKey() {
        when(streamOps.add(any(MapRecord.class))).thenReturn(RecordId.of("1-0"));

        sink.emit(rejected());

        final ArgumentCaptor<MapRecord<String, String, String>> captor =
                ArgumentCaptor.forClass(MapRecord.class);
        verify(streamOps).add(captor.capture());
        assertThat(captor.getValue().getValue())
                .doesNotContainKeys("idempotencyKey", "Idempotency-Key", "audio", "bytes", "transcript");
    }

    @SuppressWarnings("unchecked")
    @Test
    void emitLetsDataAccessExceptionPropagateForSafeEmitSwallow() {
        // safeEmit contract: the sink does NOT swallow; the controller's
        // try{emit}catch(Exception) is the single isolation point. So a Redis
        // failure must propagate out of emit().
        when(streamOps.add(any(MapRecord.class)))
                .thenThrow(new QueryTimeoutException("redis down"));

        assertThatThrownBy(() -> sink.emit(rejected()))
                .isInstanceOf(QueryTimeoutException.class);
    }
}

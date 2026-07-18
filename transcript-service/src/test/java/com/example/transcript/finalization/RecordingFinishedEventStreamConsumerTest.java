package com.example.transcript.finalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

class RecordingFinishedEventStreamConsumerTest {

    @SuppressWarnings("unchecked")
    @Test
    void processedAndIgnoredEventsAreAcknowledgedWithoutDlq() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(streamOps);
        RecordingFinishedEventHandler handler = mock(RecordingFinishedEventHandler.class);
        RecordingFinishedEventStreamConsumer consumer = consumer(redis, handler);
        MapRecord<String, String, Object> processed = record("meeting.recording.finished", "secret-a");
        MapRecord<String, String, Object> ignored = record("meeting.created", "secret-b");
        when(handler.handle(any()))
                .thenReturn(new RecordingFinishedEventHandler.HandleOutcome(
                        RecordingFinishedEventHandler.HandleResult.PROCESSED, null))
                .thenReturn(new RecordingFinishedEventHandler.HandleOutcome(
                        RecordingFinishedEventHandler.HandleResult.IGNORED, "OTHER_EVENT_TYPE"));

        consumer.handleRecord(processed);
        consumer.handleRecord(ignored);

        verify(streamOps, times(2)).acknowledge(
                "meeting:events", "transcript-finalization-v1", processed.getId());
        verify(streamOps, never()).add(any(MapRecord.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void poisonEventIsHashedAndParkedWithoutRawPayloadBeforeAck() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(streamOps);
        when(streamOps.add(any(MapRecord.class))).thenReturn(RecordId.of("2-0"));
        RecordingFinishedEventHandler handler = mock(RecordingFinishedEventHandler.class);
        when(handler.handle(any())).thenReturn(new RecordingFinishedEventHandler.HandleOutcome(
                RecordingFinishedEventHandler.HandleResult.INVALID, "PAYLOAD_SHAPE"));
        RecordingFinishedEventStreamConsumer consumer = consumer(redis, handler);
        MapRecord<String, String, Object> record = record(
                "meeting.recording.finished", "secret transcript phrase");

        consumer.handleRecord(record);

        ArgumentCaptor<MapRecord<String, String, String>> dlq = ArgumentCaptor.forClass(MapRecord.class);
        verify(streamOps).add(dlq.capture());
        assertThat(dlq.getValue().getStream())
                .isEqualTo("meeting:events:transcript-finalization:dlq");
        assertThat(dlq.getValue().getValue())
                .doesNotContainKeys("payload")
                .containsEntry("_dlqResult", "INVALID")
                .containsEntry("_dlqReason", "PAYLOAD_SHAPE")
                .containsEntry("_payloadUtf8Bytes", "24");
        assertThat(dlq.getValue().getValue().get("_payloadSha256"))
                .matches("[0-9a-f]{64}");
        verify(streamOps).acknowledge(
                "meeting:events", "transcript-finalization-v1", record.getId());
    }

    @SuppressWarnings("unchecked")
    @Test
    void dlqFailureLeavesPoisonEventPendingForRetry() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(streamOps);
        when(streamOps.add(any(MapRecord.class)))
                .thenThrow(new DataAccessResourceFailureException("redis unavailable"));
        RecordingFinishedEventHandler handler = mock(RecordingFinishedEventHandler.class);
        when(handler.handle(any())).thenReturn(new RecordingFinishedEventHandler.HandleOutcome(
                RecordingFinishedEventHandler.HandleResult.DEAD, "INBOX_KEY_DIVERGENCE"));
        RecordingFinishedEventStreamConsumer consumer = consumer(redis, handler);

        consumer.handleRecord(record("meeting.recording.finished", "secret"));

        verify(streamOps, never()).acknowledge(any(), any(), any(RecordId[].class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void missingDlqRecordIdLeavesPoisonEventPendingForRetry() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(streamOps);
        when(streamOps.add(any(MapRecord.class))).thenReturn(null);
        RecordingFinishedEventHandler handler = mock(RecordingFinishedEventHandler.class);
        when(handler.handle(any())).thenReturn(new RecordingFinishedEventHandler.HandleOutcome(
                RecordingFinishedEventHandler.HandleResult.INVALID, "PAYLOAD_SHAPE"));
        RecordingFinishedEventStreamConsumer consumer = consumer(redis, handler);

        consumer.handleRecord(record("meeting.recording.finished", "secret"));

        verify(streamOps, never()).acknowledge(any(), any(), any(RecordId[].class));
    }

    private RecordingFinishedEventStreamConsumer consumer(
            StringRedisTemplate redis, RecordingFinishedEventHandler handler) {
        return new RecordingFinishedEventStreamConsumer(
                redis, handler,
                new TranscriptFinalizationProperties.RecordingFinishedConsumer());
    }

    private MapRecord<String, String, Object> record(String eventType, String payload) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("schemaVersion", "meeting.event.v1");
        fields.put("eventType", eventType);
        fields.put("producer", "meeting-service");
        fields.put("aggregateType", "meeting.recording");
        fields.put("aggregateId", "00000000-0000-0000-0000-000000000003");
        fields.put("aggregateRevision", "1");
        fields.put("eventKey", "meeting.recording|session|meeting.recording.finished|1");
        fields.put("tenantId", "00000000-0000-0000-0000-000000000001");
        fields.put("meetingId", "00000000-0000-0000-0000-000000000002");
        fields.put("payload", payload);
        return StreamRecords.mapBacked(fields).withStreamKey("meeting:events");
    }
}

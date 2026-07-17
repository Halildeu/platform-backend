package com.example.transcript.directstt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

class DirectSttTranscriptResultStreamConsumerTest {

    @SuppressWarnings("unchecked")
    @Test
    void invalidEventIsParkedInMetadataOnlyDlqBeforeAck() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(streamOps);
        when(streamOps.add(any(MapRecord.class))).thenReturn(RecordId.of("2-0"));

        DirectSttTranscriptResultHandler handler = mock(DirectSttTranscriptResultHandler.class);
        when(handler.handle(any(), any()))
                .thenReturn(DirectSttTranscriptResultHandler.HandleOutcome.invalid("meetingId must be a UUID"));

        DirectSttTranscriptResultConsumerProperties props = new DirectSttTranscriptResultConsumerProperties();
        DirectSttTranscriptResultStreamConsumer consumer =
                new DirectSttTranscriptResultStreamConsumer(redis, handler, props);

        Map<String, Object> fields = new LinkedHashMap<>(DirectSttTranscriptResultEventTest.validFields());
        fields.put("textDraft", "secret transcript phrase");
        MapRecord<String, String, Object> record =
                StreamRecords.mapBacked(fields).withStreamKey("transcript:direct-stt-results");

        consumer.handleRecord(record);

        ArgumentCaptor<MapRecord<String, String, String>> dlqCaptor =
                ArgumentCaptor.forClass(MapRecord.class);
        verify(streamOps).add(dlqCaptor.capture());
        assertThat(dlqCaptor.getValue().getStream()).isEqualTo("transcript:direct-stt-results:dlq");
        assertThat(dlqCaptor.getValue().getValue())
                .doesNotContainKeys("textDraft", "userId")
                .containsEntry("textLength", "13")
                .containsEntry("_dlqReason", "meetingId must be a UUID");
        verify(streamOps).acknowledge(
                "transcript:direct-stt-results", "transcript-service-v1", record.getId());
    }

    @SuppressWarnings("unchecked")
    @Test
    void missingMappingStaysUnackedForBoundedResolverRetry() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(streamOps);
        DirectSttTranscriptResultHandler handler = mock(DirectSttTranscriptResultHandler.class);
        when(handler.handle(any(), any()))
                .thenReturn(DirectSttTranscriptResultHandler.HandleOutcome.pending("MAPPING_NOT_FOUND"));
        DirectSttTranscriptResultStreamConsumer consumer = new DirectSttTranscriptResultStreamConsumer(
                redis, handler, new DirectSttTranscriptResultConsumerProperties());
        Map<String, Object> fields = new LinkedHashMap<>(DirectSttTranscriptResultEventTest.validFields());
        MapRecord<String, String, Object> record = StreamRecords
                .mapBacked(fields)
                .withStreamKey("transcript:direct-stt-results");

        consumer.handleRecord(record);

        verify(streamOps, never()).add(any(MapRecord.class));
        verify(streamOps, never()).acknowledge(any(), any(), any(RecordId[].class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void exhaustedMappingWritesMetadataOnlyDlqThenAcknowledges() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(streamOps);
        when(streamOps.add(any(MapRecord.class))).thenReturn(RecordId.of("2-0"));
        DirectSttTranscriptResultHandler handler = mock(DirectSttTranscriptResultHandler.class);
        when(handler.handle(any(), any()))
                .thenReturn(DirectSttTranscriptResultHandler.HandleOutcome.dead("MAPPING_NOT_FOUND"));
        DirectSttTranscriptResultStreamConsumer consumer = new DirectSttTranscriptResultStreamConsumer(
                redis, handler, new DirectSttTranscriptResultConsumerProperties());
        Map<String, Object> fields = new LinkedHashMap<>(DirectSttTranscriptResultEventTest.validFields());
        fields.put("textDraft", "secret transcript phrase");
        MapRecord<String, String, Object> record = StreamRecords
                .mapBacked(fields).withStreamKey("transcript:direct-stt-results");

        consumer.handleRecord(record);

        ArgumentCaptor<MapRecord<String, String, String>> dlq = ArgumentCaptor.forClass(MapRecord.class);
        verify(streamOps).add(dlq.capture());
        assertThat(dlq.getValue().getValue())
                .containsEntry("_dlqReason", "MAPPING_NOT_FOUND")
                .doesNotContainKeys("textDraft", "userId");
        verify(streamOps).acknowledge(
                "transcript:direct-stt-results", "transcript-service-v1", record.getId());
    }
}

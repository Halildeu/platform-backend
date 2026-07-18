package com.example.meeting.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisMeetingEventPublisherTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void xaddCarriesCanonicalPayloadBytesWithoutParseOrReserialization() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations operations = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(operations);
        when(operations.add(any(MapRecord.class))).thenReturn(RecordId.of("1-0"));
        RedisMeetingEventPublisher publisher = new RedisMeetingEventPublisher(redis, "meeting:events");
        String exactPayload = "{\"z\":1, \"raw\":\"exact bytes\"}";
        UUID aggregateId = UUID.randomUUID();
        MeetingEventMessage message = new MeetingEventMessage(
                "meeting.recording|" + aggregateId + "|meeting.recording.finished|1",
                "meeting.recording.finished",
                "meeting.recording",
                aggregateId,
                1,
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                exactPayload);

        publisher.publish(message);

        ArgumentCaptor<MapRecord> record = ArgumentCaptor.forClass(MapRecord.class);
        verify(operations).add(record.capture());
        assertThat(record.getValue().getStream()).isEqualTo("meeting:events");
        Map<?, ?> fields = (Map<?, ?>) record.getValue().getValue();
        assertThat(fields.get("payload")).isEqualTo(exactPayload);
        assertThat(fields.get("aggregateType")).isEqualTo("meeting.recording");
        assertThat(fields.get("aggregateRevision")).isEqualTo("1");
        assertThat(fields.get("orgId")).isEqualTo("");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void nullXaddResultFailsWithoutAcknowledgingDelivery() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations operations = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(operations);
        when(operations.add(any(MapRecord.class))).thenReturn(null);
        RedisMeetingEventPublisher publisher = new RedisMeetingEventPublisher(redis, "meeting:events");
        UUID id = UUID.randomUUID();
        MeetingEventMessage message = new MeetingEventMessage(
                "key", "meeting.recording.finished", "meeting.recording", id, 1,
                UUID.randomUUID(), UUID.randomUUID(), null, "{}");

        assertThatThrownBy(() -> publisher.publish(message))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("XADD returned no record id");
    }
}

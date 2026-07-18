package com.example.transcript.events;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Concrete at-least-once publisher for the shared meeting event stream. */
@Component
@Primary
@ConditionalOnProperty(name = "transcript.events.redis.enabled", havingValue = "true")
public class RedisTranscriptMeetingEventPublisher implements TranscriptMeetingEventPublisher {

    private final StringRedisTemplate redis;
    private final String streamKey;

    public RedisTranscriptMeetingEventPublisher(
            StringRedisTemplate redis,
            @Value("${transcript.events.redis.stream-key:meeting:events}") String streamKey) {
        this.redis = redis;
        this.streamKey = streamKey;
    }

    @Override
    public void publish(TranscriptMeetingEventMessage event) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("eventKey", event.eventKey());
        fields.put("eventType", event.eventType());
        fields.put("aggregateId", event.aggregateId().toString());
        fields.put("meetingId", event.meetingId().toString());
        fields.put("tenantId", event.tenantId().toString());
        fields.put("orgId", event.orgId() == null ? "" : event.orgId().toString());
        fields.put("payload", event.payloadJson());
        RecordId id = redis.opsForStream().add(
                StreamRecords.mapBacked(fields).withStreamKey(streamKey));
        if (id == null) {
            throw new IllegalStateException("Redis XADD returned no record id");
        }
    }
}

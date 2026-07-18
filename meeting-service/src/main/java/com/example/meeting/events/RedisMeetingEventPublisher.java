package com.example.meeting.events;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Concrete at-least-once publisher for the shared meeting event Redis Stream. */
@Component
@Primary
@ConditionalOnProperty(name = "meeting.events.redis.enabled", havingValue = "true")
public class RedisMeetingEventPublisher implements MeetingEventPublisher {

    private final StringRedisTemplate redis;
    private final String streamKey;

    public RedisMeetingEventPublisher(
            final StringRedisTemplate redis,
            @Value("${meeting.events.redis.stream-key:meeting:events}") final String streamKey) {
        this.redis = redis;
        this.streamKey = streamKey;
    }

    @Override
    public void publish(final MeetingEventMessage event) {
        final Map<String, String> fields = new LinkedHashMap<>();
        fields.put("eventKey", event.eventKey());
        fields.put("eventType", event.eventType());
        fields.put("aggregateType", event.aggregateType());
        fields.put("aggregateId", event.aggregateId().toString());
        fields.put("aggregateRevision", Long.toString(event.aggregateRevision()));
        fields.put("meetingId", event.meetingId().toString());
        fields.put("tenantId", event.tenantId().toString());
        fields.put("orgId", event.orgId() == null ? "" : event.orgId().toString());
        // Exact canonical serializer output: no parse/re-serialize step is allowed here.
        fields.put("payload", event.payloadJson());
        final RecordId id = redis.opsForStream().add(
                StreamRecords.mapBacked(fields).withStreamKey(streamKey));
        if (id == null) {
            throw new IllegalStateException("Redis XADD returned no record id");
        }
    }
}

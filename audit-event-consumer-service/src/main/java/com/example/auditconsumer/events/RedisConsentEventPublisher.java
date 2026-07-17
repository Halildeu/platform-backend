package com.example.auditconsumer.events;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Concrete at-least-once transport for the shared meeting event stream. */
@Component
@Primary
@ConditionalOnProperty(name = "audit.consent-events.redis.enabled", havingValue = "true")
public class RedisConsentEventPublisher implements ConsentEventPublisher {

    private final StringRedisTemplate redis;
    private final String streamKey;

    public RedisConsentEventPublisher(
            StringRedisTemplate redis,
            @Value("${audit.consent-events.redis.stream-key:meeting:events}") String streamKey) {
        this.redis = redis;
        this.streamKey = streamKey;
    }

    @Override
    public void publish(ConsentEventMessage event) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("eventKey", event.eventKey());
        fields.put("eventType", event.eventType());
        fields.put("aggregateId", event.aggregateId().toString());
        fields.put("meetingId", event.meetingId().toString());
        fields.put("tenantId", event.tenantId().toString());
        fields.put("orgId", event.orgId().toString());
        fields.put("payload", event.payloadJson());
        MapRecord<String, String, String> record = StreamRecords.mapBacked(fields).withStreamKey(streamKey);
        // Do not MAXLEN-trim an authoritative event stream at the producer.
        // Redis trimming is consumer-ack unaware and can delete an unconsumed
        // event after this outbox row has already been marked PUBLISHED.
        RecordId result = redis.opsForStream().add(record);
        if (result == null) {
            throw new IllegalStateException("Redis XADD returned no record id");
        }
    }
}

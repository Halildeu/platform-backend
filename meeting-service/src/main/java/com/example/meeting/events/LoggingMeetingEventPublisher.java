package com.example.meeting.events;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Fail-closed fallback while the Redis transport is disabled.
 *
 * <p>The old logging publisher acknowledged delivery without a durable consumer-visible
 * side effect. Throwing here prevents any accidental caller from marking a row published.
 */
@Component
@ConditionalOnProperty(
        name = "meeting.events.redis.enabled",
        havingValue = "false",
        matchIfMissing = true)
public class LoggingMeetingEventPublisher implements MeetingEventPublisher {

    @Override
    public void publish(final MeetingEventMessage event) {
        throw new IllegalStateException("meeting event Redis transport is disabled");
    }
}

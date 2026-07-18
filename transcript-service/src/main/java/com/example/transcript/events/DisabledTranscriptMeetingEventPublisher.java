package com.example.transcript.events;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Fail-closed fallback: a disabled transport must never mark an outbox row published. */
@Component
@ConditionalOnProperty(
        name = "transcript.events.redis.enabled",
        havingValue = "false",
        matchIfMissing = true)
public class DisabledTranscriptMeetingEventPublisher implements TranscriptMeetingEventPublisher {
    @Override
    public void publish(TranscriptMeetingEventMessage event) {
        throw new IllegalStateException("transcript meeting-event transport is disabled");
    }
}

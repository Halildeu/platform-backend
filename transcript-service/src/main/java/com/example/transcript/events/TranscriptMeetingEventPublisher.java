package com.example.transcript.events;

/** At-least-once transport port; consumers de-duplicate by eventKey. */
public interface TranscriptMeetingEventPublisher {
    void publish(TranscriptMeetingEventMessage event);
}

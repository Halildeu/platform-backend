package com.example.meeting.notify;

import com.example.meeting.events.MeetingEventMessage;

/**
 * Second delivery target of the meeting-event outbox (Faz 24 Görevler dilim-4b):
 * turns an assignment / hand-over event into a notification-orchestrator system
 * intent for the assignee. Implementations must be idempotent per event key and
 * throw on failure so the outbox poller retries the row.
 */
public interface AssignmentNotificationSink {

    /** True when {@code eventType} is an assignment event this sink delivers. */
    boolean handles(String eventType);

    /** Deliver the intent; throw a {@link RuntimeException} to have the outbox row retried. */
    void deliver(MeetingEventMessage message);

    /** Default-off sink: nothing is delivered, the Redis publish path is unchanged. */
    AssignmentNotificationSink NOOP = new AssignmentNotificationSink() {
        @Override
        public boolean handles(String eventType) {
            return false;
        }

        @Override
        public void deliver(MeetingEventMessage message) {
            // no-op
        }
    };
}

package com.example.meeting.config;

/** Enforces the atomic rollout contract for meeting event publication. */
public final class MeetingEventRolloutGuard {

    static final String ERROR_MESSAGE =
            "meeting.events.redis.enabled and meeting.events.outbox.poller.enabled "
                    + "must be enabled or disabled together";

    private MeetingEventRolloutGuard() {
    }

    public static void validate(final boolean redisPublisherEnabled, final boolean outboxPollerEnabled) {
        if (redisPublisherEnabled != outboxPollerEnabled) {
            throw new IllegalStateException(ERROR_MESSAGE);
        }
    }
}

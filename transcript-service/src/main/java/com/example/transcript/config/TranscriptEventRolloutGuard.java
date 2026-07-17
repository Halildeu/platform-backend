package com.example.transcript.config;

/** Enforces the atomic rollout contract for transcript event publication. */
public final class TranscriptEventRolloutGuard {

    static final String ERROR_MESSAGE =
            "transcript.events.redis.enabled and "
                    + "transcript.events.outbox.poller.enabled must be enabled or disabled together";

    private TranscriptEventRolloutGuard() {}

    public static void validate(boolean redisPublisherEnabled, boolean outboxPollerEnabled) {
        if (redisPublisherEnabled != outboxPollerEnabled) {
            throw new IllegalStateException(ERROR_MESSAGE);
        }
    }
}

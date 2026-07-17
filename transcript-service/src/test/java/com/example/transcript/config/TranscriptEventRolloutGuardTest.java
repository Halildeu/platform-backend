package com.example.transcript.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TranscriptEventRolloutGuardTest {

    @Test
    void acceptsBothFlagsDisabled() {
        assertThatCode(() -> TranscriptEventRolloutGuard.validate(false, false))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsBothFlagsEnabled() {
        assertThatCode(() -> TranscriptEventRolloutGuard.validate(true, true))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsPollerWithoutRedisPublisher() {
        assertThatThrownBy(() -> TranscriptEventRolloutGuard.validate(false, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(TranscriptEventRolloutGuard.ERROR_MESSAGE);
    }

    @Test
    void rejectsRedisPublisherWithoutPoller() {
        assertThatThrownBy(() -> TranscriptEventRolloutGuard.validate(true, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(TranscriptEventRolloutGuard.ERROR_MESSAGE);
    }
}

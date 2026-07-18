package com.example.meeting.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MeetingEventRolloutGuardTest {

    @Test
    void matchingFlagsAreAcceptedIncludingDefaultOff() {
        MeetingEventRolloutGuard.validate(false, false);
        MeetingEventRolloutGuard.validate(true, true);
    }

    @Test
    void eitherMismatchFailsClosed() {
        assertThatThrownBy(() -> MeetingEventRolloutGuard.validate(true, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be enabled or disabled together");
        assertThatThrownBy(() -> MeetingEventRolloutGuard.validate(false, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be enabled or disabled together");
    }

    @Test
    void pollerEnabledPublisherDisabledFailsApplicationStartup() {
        new ApplicationContextRunner()
                .withUserConfiguration(MeetingEventRolloutConfiguration.class)
                .withPropertyValues(
                        "meeting.events.redis.enabled=false",
                        "meeting.events.outbox.poller.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .rootCause()
                            .hasMessageContaining("must be enabled or disabled together");
                });
    }
}
